package main

import (
	"encoding/json"
	"errors"
	"maps"
	"os"
	"slices"
	"sync"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/tka"
)

const tailnetStorePrefix = "_tailnet-lock/"

// One atomic value per profile keeps the history and its compaction metadata together.
// Trust changes are infrequent; reads use this in-memory snapshot, and upstream compacts it.
type tailnetStore struct {
	mu       sync.Mutex
	store    *javaStore
	key      ipn.StateKey
	state    tailnetState
	children map[tka.AUMHash][]tka.AUMHash // Rebuilt lazily after a mutation.
}

type tailnetState struct {
	Version  int
	Updates  map[tka.AUMHash]tailnetUpdate
	Ancestor *tka.AUMHash
}

type tailnetUpdate struct {
	Data    []byte // The upstream AUM's canonical CBOR, independent of our envelope.
	Created int64  // First commit time, retained across duplicate commits and restarts.
}

var _ tka.CompactableChonk = (*tailnetStore)(nil)

// OpenTailnetLock is called by the patched backend, including before it contacts control.
// A nil authority means no saved history. Invalid history must never trigger re-bootstrap.
func (s *javaStore) OpenTailnetLock(profile ipn.ProfileID) (*tka.Authority, tka.CompactableChonk, error) {
	c := &tailnetStore{store: s, key: ipn.StateKey(tailnetStorePrefix + string(profile)),
		state: tailnetState{Version: 1, Updates: make(map[tka.AUMHash]tailnetUpdate)}}
	data, err := s.ReadState(c.key)
	if errors.Is(err, ipn.ErrStateNotExist) {
		return nil, c, nil
	}
	if err != nil {
		return nil, nil, err
	}
	var saved tailnetState
	if json.Unmarshal(data, &saved) != nil || saved.Version != 1 || len(saved.Updates) == 0 {
		return nil, nil, s.fail()
	}
	c.state = saved
	for hash, update := range c.state.Updates {
		var aum tka.AUM
		if aum.Unserialize(update.Data) != nil || aum.StaticValidate() != nil || aum.Hash() != hash || update.Created <= 0 {
			return nil, nil, s.fail()
		}
	}
	authority, err := tka.Open(c)
	if err != nil {
		return nil, nil, s.fail()
	}
	return authority, c, nil
}

func (s *javaStore) DeleteTailnetLock(profile ipn.ProfileID) error {
	return s.WriteState(ipn.StateKey(tailnetStorePrefix+string(profile)), nil)
}

func (c *tailnetStore) AUM(hash tka.AUMHash) (tka.AUM, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.aum(hash)
}

func (c *tailnetStore) aum(hash tka.AUMHash) (tka.AUM, error) {
	update, ok := c.state.Updates[hash]
	if !ok {
		return tka.AUM{}, os.ErrNotExist
	}
	var aum tka.AUM
	if aum.Unserialize(update.Data) != nil {
		return tka.AUM{}, c.store.fail()
	}
	return aum, nil
}

func (c *tailnetStore) ChildAUMs(parent tka.AUMHash) ([]tka.AUM, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.index(); err != nil {
		return nil, err
	}
	var children []tka.AUM
	for _, hash := range c.children[parent] {
		aum, err := c.aum(hash)
		if err != nil {
			return nil, err
		}
		children = append(children, aum)
	}
	return children, nil
}

func (c *tailnetStore) Heads() ([]tka.AUM, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.index(); err != nil {
		return nil, err
	}
	var heads []tka.AUM
	for hash := range c.state.Updates {
		if len(c.children[hash]) != 0 {
			continue
		}
		aum, err := c.aum(hash)
		if err != nil {
			return nil, err
		}
		heads = append(heads, aum)
	}
	return heads, nil
}

func (c *tailnetStore) index() error {
	if c.children != nil {
		return nil
	}
	c.children = make(map[tka.AUMHash][]tka.AUMHash)
	for hash := range c.state.Updates {
		aum, err := c.aum(hash)
		if err != nil {
			return err
		}
		if parent, ok := aum.Parent(); ok {
			c.children[parent] = append(c.children[parent], hash)
		}
	}
	return nil
}

func (c *tailnetStore) CommitVerifiedAUMs(updates []tka.AUM) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	next := c.state
	next.Updates = maps.Clone(next.Updates)
	for _, aum := range updates {
		hash := aum.Hash()
		if _, exists := next.Updates[hash]; !exists {
			next.Updates[hash] = tailnetUpdate{Data: aum.Serialize(), Created: time.Now().UnixNano()}
		}
	}
	if len(next.Updates) == len(c.state.Updates) {
		return nil
	}
	return c.save(next)
}

func (c *tailnetStore) SetLastActiveAncestor(hash tka.AUMHash) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.state.Ancestor != nil && *c.state.Ancestor == hash {
		return nil
	}
	next := c.state
	next.Ancestor = &hash
	return c.save(next)
}

func (c *tailnetStore) LastActiveAncestor() (*tka.AUMHash, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.state.Ancestor == nil {
		return nil, nil
	}
	hash := *c.state.Ancestor
	return &hash, nil
}

func (c *tailnetStore) AllAUMs() ([]tka.AUMHash, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return slices.Collect(maps.Keys(c.state.Updates)), nil
}

func (c *tailnetStore) CommitTime(hash tka.AUMHash) (time.Time, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	update, ok := c.state.Updates[hash]
	if !ok {
		return time.Time{}, os.ErrNotExist
	}
	return time.Unix(0, update.Created), nil
}

func (c *tailnetStore) PurgeAUMs(hashes []tka.AUMHash) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	next := c.state
	next.Updates = maps.Clone(next.Updates)
	for _, hash := range hashes {
		delete(next.Updates, hash)
	}
	if len(next.Updates) == len(c.state.Updates) {
		return nil
	}
	return c.save(next)
}

func (c *tailnetStore) RemoveAll() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.store.WriteState(c.key, nil); err != nil {
		return err
	}
	c.state = tailnetState{Version: 1, Updates: make(map[tka.AUMHash]tailnetUpdate)}
	c.children = nil
	return nil
}

// save publishes the new in-memory state only after the atomic callback succeeds.
func (c *tailnetStore) save(next tailnetState) error {
	data, err := json.Marshal(next)
	if err != nil {
		return c.store.fail()
	}
	if err := c.store.WriteState(c.key, data); err != nil {
		return err
	}
	c.state = next
	c.children = nil
	return nil
}
