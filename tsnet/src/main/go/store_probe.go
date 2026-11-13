//go:build klethron_test

package main

import "C"

import (
	"bytes"
	"errors"
	"strconv"

	"tailscale.com/ipn"
)

func probeStore(handle string) string {
	id, _ := strconv.ParseInt(handle, 10, 64)
	s := lookupNode(C.longlong(id)).store
	key := ipn.StateKey("probe-\x00🔒")
	value := make([]byte, 256)
	for i := range value {
		value[i] = byte(i)
	}
	for _, value := range [][]byte{value, {}, nil} {
		if err := s.WriteState(key, value); err != nil {
			return "write failed"
		}
		got, err := s.ReadState(key)
		if value == nil {
			if !errors.Is(err, ipn.ErrStateNotExist) {
				return "delete failed"
			}
		} else if err != nil || got == nil || !bytes.Equal(value, got) {
			return "read failed"
		}
	}
	return "ok"
}
