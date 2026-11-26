package tsbridge

import (
	"strings"
	"testing"
)

func TestRecoverTurnsPanicIntoError(t *testing.T) {
	err := Recover("td_test", func() error { panic("boom") })
	if err == nil || !strings.Contains(err.Error(), "td_test") || !strings.Contains(err.Error(), "boom") {
		t.Fatalf("unexpected error %v", err)
	}
	if !strings.Contains(Logs(), "boom") {
		t.Fatal("the panic was not recorded in the log ring")
	}
}

func TestRecoverPassesResult(t *testing.T) {
	if err := Recover("td_test", func() error { return nil }); err != nil {
		t.Fatalf("unexpected error %v", err)
	}
}
