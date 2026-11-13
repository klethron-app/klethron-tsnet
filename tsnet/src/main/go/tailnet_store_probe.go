//go:build klethron_test

package main

import "C"

import (
	"context"
	"errors"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/tailcfg"
	"tailscale.com/tailcfg/nodecap"
	"tailscale.com/tka"
	"tailscale.com/types/key"
)

var lockExpectSaved atomic.Bool
var lockMissingHead atomic.Int32

func probeTailnetLock(argument string) string {
	handle, action, _ := strings.Cut(argument, " ")
	id, _ := strconv.ParseInt(handle, 10, 64)
	n := lookupNode(C.longlong(id))
	n.lifecycle.RLock()
	defer n.lifecycle.RUnlock()
	ctx, cancel := context.WithTimeout(n.ctx, 10*time.Second)
	defer cancel()
	if action == "init" {
		status, err := n.client.TailnetLockStatus(ctx)
		if err != nil {
			return "status failed"
		}
		_, err = n.client.TailnetLockInit(ctx, []tka.Key{{Kind: tka.Key25519,
			Public: status.PublicKey.Verifier(), Votes: 1}}, [][]byte{tka.DisablementKDF([]byte("local-test-disable"))}, nil)
		if err != nil {
			for _, stage := range []string{"pricing plan", "no node-key", "tka.Create", "init-begin RPC", "generating signature", "decoding JSON"} {
				if strings.Contains(err.Error(), stage) {
					return "init failed: " + stage
				}
			}
			return "init failed"
		}
		// testcontrol's init endpoint does not wake existing map streams.
		loginProbe.Lock()
		loginProbe.control.AddFakeNode() // An unsigned peer that must be filtered.
		loginProbe.control.SetNodeCapMap(*status.NodeKey, tailcfg.NodeCapMap{nodecap.TailnetLock: nil})
		loginProbe.Unlock()
	}
	for {
		status, err := n.client.TailnetLockStatus(ctx)
		if err != nil {
			return "status failed"
		}
		if status.Enabled && status.NodeKeySigned && len(status.FilteredPeers) > 0 {
			if lockMissingHead.Load() != 0 {
				return "restart forgot authority head"
			}
			return "ok"
		}
		select {
		case <-ctx.Done():
			if !status.Enabled {
				return "authority not enabled"
			}
			if !status.NodeKeySigned {
				return "node key not signed"
			}
			return "unsigned peer not filtered"
		case <-time.After(10 * time.Millisecond):
		}
	}
}

// Exercise the real JNI callbacks with signed history, including compaction and reopening.
func probeTailnetStore(argument string) string {
	handle, action, _ := strings.Cut(argument, " ")
	id, _ := strconv.ParseInt(handle, 10, 64)
	n := lookupNode(C.longlong(id))
	n.lifecycle.RLock()
	defer n.lifecycle.RUnlock()
	signer := key.NewTLPrivate()
	trusted := tka.Key{Kind: tka.Key25519, Public: signer.Public().Verifier(), Votes: 1}
	const profile ipn.ProfileID = "probe-authority"
	authority, storage, err := n.store.OpenTailnetLock(profile)
	if err != nil {
		return "open failed"
	}
	if authority != nil {
		return "unexpected existing authority"
	}
	authority, genesis, err := tka.Create(storage, tka.State{Keys: []tka.Key{trusted},
		DisablementValues: [][]byte{tka.DisablementKDF([]byte("local-test-disable"))}}, signer)
	if err != nil {
		return "create failed"
	}
	if action == "seed" {
		return "ok"
	}
	created, err := storage.CommitTime(genesis.Hash())
	if err != nil {
		return "commit time failed"
	}
	if err := storage.CommitVerifiedAUMs([]tka.AUM{genesis}); err != nil {
		return "duplicate commit failed"
	}
	// A sibling profile must not see this history.
	if sibling, _, err := n.store.OpenTailnetLock("probe-other"); err != nil || sibling != nil {
		return "profile isolation failed"
	}
	for i := range 150 {
		updater := authority.NewUpdater(signer)
		if err := updater.SetKeyMeta(trusted.MustID(), map[string]string{"revision": strconv.Itoa(i)}); err != nil {
			return "update failed"
		}
		updates, err := updater.Finalize(storage)
		if err != nil || authority.Inform(storage, updates) != nil {
			return "commit failed"
		}
	}
	head := authority.Head()
	authority, storage, err = n.store.OpenTailnetLock(profile)
	if err != nil || authority == nil || authority.Head() != head {
		return "reopen failed"
	}
	if timestamp, err := storage.CommitTime(genesis.Hash()); err != nil || !timestamp.Equal(created) {
		return "commit time changed"
	}
	before, _ := n.store.ReadState(ipn.StateKey(tailnetStorePrefix + string(profile)))
	if err := authority.Compact(storage, tka.CompactionOptions{MinChain: 24, MinAge: time.Nanosecond}); err != nil {
		return "compaction failed"
	}
	after, _ := n.store.ReadState(ipn.StateKey(tailnetStorePrefix + string(profile)))
	if len(after) >= len(before) {
		return "compaction did not shrink storage"
	}
	authority, storage, err = n.store.OpenTailnetLock(profile)
	if err != nil || authority == nil || authority.Head() != head || !authority.KeyTrusted(trusted.MustID()) {
		return "compacted authority did not reopen"
	}
	if _, err := storage.AUM(genesis.Hash()); !errors.Is(err, os.ErrNotExist) {
		return "compaction retained old history"
	}
	if err := storage.RemoveAll(); err != nil {
		return "remove failed"
	}
	if _, err := n.store.ReadState(ipn.StateKey(tailnetStorePrefix + string(profile))); !errors.Is(err, ipn.ErrStateNotExist) {
		return "remove did not delete the record"
	}
	if authority, _, err := n.store.OpenTailnetLock(profile); err != nil || authority != nil {
		return "removed authority returned"
	}
	return "ok"
}
