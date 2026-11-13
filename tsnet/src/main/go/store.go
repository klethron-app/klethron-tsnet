package main

// #include <stdlib.h>
// #include "store.h"
import "C"

import (
	"errors"
	"math"
	"sync"
	"sync/atomic"
	"unsafe"

	"tailscale.com/ipn"
)

var errStateStore = errors.New("state store failed")

type javaStore struct {
	mu     sync.Mutex
	handle *C.state_store
	failed atomic.Bool
	node   *node
}

func (s *javaStore) fail() error {
	if !s.failed.Swap(true) {
		s.node.cancel()
		go s.node.close() // A callback can hold backend locks; shutdown must wait outside it.
	}
	return errStateStore
}

func (s *javaStore) ReadState(key ipn.StateKey) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failed.Load() || s.handle == nil {
		return nil, errStateStore
	}
	if len(key) > math.MaxInt32 {
		return nil, s.fail()
	}
	var data unsafe.Pointer
	var length C.int
	name := string(key)
	result := C.state_read(s.handle, (*C.char)(unsafe.Pointer(unsafe.StringData(name))), C.int(len(name)), &data, &length)
	defer C.free(data)
	switch result {
	case 0:
		return C.GoBytes(data, length), nil
	case 1:
		return nil, ipn.ErrStateNotExist
	default:
		return nil, s.fail()
	}
}

func (s *javaStore) WriteState(key ipn.StateKey, value []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failed.Load() || s.handle == nil {
		return errStateStore
	}
	if len(value) > math.MaxInt32 || len(key) > math.MaxInt32 {
		return s.fail()
	}
	length := len(value)
	if value == nil {
		length = -1
	}
	name := string(key)
	if C.state_write(s.handle, (*C.char)(unsafe.Pointer(unsafe.StringData(name))), C.int(len(name)),
		unsafe.Pointer(unsafe.SliceData(value)), C.int(length)) != 0 {
		return s.fail()
	}
	return nil
}

func (s *javaStore) close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.handle != nil {
		C.state_close(s.handle)
		s.handle = nil
	}
}
