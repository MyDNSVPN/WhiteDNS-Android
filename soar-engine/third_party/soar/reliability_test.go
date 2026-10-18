package soar

import (
	"testing"
	"time"
)

func TestQuerySizingKeepsWorkingNamesThroughCommonLoss(t *testing.T) {
	q := newQuerySizer(101, 253)
	q.earned = 3
	now := time.Now()
	for i := 0; i < 64; i++ {
		_, sample, _ := q.next(now)
		q.outcome(sample, i%4 < 2, now)
		now = now.Add(10 * time.Millisecond)
	}
	if q.ok != 253 {
		t.Fatalf("shared loss suppressed a working query budget: %d", q.ok)
	}
	for i := 0; i < 512; i++ {
		_, sample, _ := q.next(now)
		lost := i%24 < 12
		q.outcome(sample, lost, now)
		now = now.Add(10 * time.Millisecond)
	}
	if q.ok != 253 {
		t.Fatalf("ordinary congestion permanently downgraded query budget: %d", q.ok)
	}
}
func TestQuerySizingFindsIntermediateNameBudget(t *testing.T) {
	q := newQuerySizer(101, 253)
	q.earned = 3
	now := time.Now()
	for i := 0; i < 6000 && q.ok < 145; i++ {
		cap, sample, _ := q.next(now)
		if cap < 101 || cap > 253 {
			t.Fatal("budget escaped bounds", cap)
		}
		q.outcome(sample, cap > 150, now)
		now = now.Add(20 * time.Millisecond)
	}
	if q.ok < 145 || q.ok > 150 {
		t.Fatalf("failed to find a usable intermediate query size: %d", q.ok)
	}
}
func TestQuerySizingRecoversAfterSizeSpecificBlock(t *testing.T) {
	q := newQuerySizer(101, 253)
	q.earned = 3
	now := time.Now()
	for i := 0; i < 64; i++ {
		_, sample, _ := q.next(now)
		q.outcome(sample, false, now)
	}
	if q.ok != 253 {
		t.Fatal("healthy path not promoted")
	}
	for i := 0; i < 256 && q.ok > 101; i++ {
		cap, sample, _ := q.next(now)
		q.outcome(sample, cap > 101, now)
		now = now.Add(20 * time.Millisecond)
	}
	if q.ok != 101 {
		t.Fatal("size-specific filtering was not respected", q.ok)
	}
	now = now.Add(20 * time.Minute)
	for i := 0; i < 160 && q.ok < 253; i++ {
		_, sample, _ := q.next(now)
		q.outcome(sample, false, now)
		now = now.Add(20 * time.Millisecond)
	}
	if q.ok != 253 {
		t.Fatal("temporary filtering pinned the path forever", q.ok)
	}
}
func startupFixture(t *testing.T, n, bytes int) *session {
	t.Helper()
	s := newSession(true, streamConfig{StreamWindow: initialWindow, SendBuffer: 65536, MaxStreams: 64}, nil, nil)
	for i := 0; i < n; i++ {
		st, e := s.openStream()
		if e != nil {
			t.Fatal(e)
		}
		st.sbuf = make([]byte, bytes)
		st.spending.add(0, uint64(bytes))
	}
	return s
}
func TestSmallQueryBudgetsCompleteConcurrentInitialFlights(t *testing.T) {
	s := startupFixture(t, 12, 2048)
	now := time.Now()
	started := map[uint64]bool{}
	complete := map[uint64]bool{}
	for i := 0; i < 5000 && len(complete) < 12; i++ {
		_, _, _, packet := s.build(nil, 22, now, 0)
		if packet == nil {
			t.Fatal("initial flights stopped making progress")
		}
		for _, r := range packet.streams {
			started[r.id] = true
			if r.hi == 2048 {
				complete[r.id] = true
				s.streams[r.id].rhigh = 1
			}
		}
		if len(started)-len(complete) > 2 {
			t.Fatalf("spread partial handshakes across %d destinations", len(started)-len(complete))
		}
		s.acked(packet)
		now = now.Add(time.Millisecond)
	}
	if len(complete) != 12 {
		t.Fatal("not every queued initial flight completed", len(complete))
	}
}
func TestEstablishedBulkUploadProgressesDuringInitialFlights(t *testing.T) {
	s := startupFixture(t, 5, 4096)
	s.streams[0].rhigh = 1
	now := time.Now()
	bulk, startup := uint64(0), uint64(0)
	for i := 0; i < 20; i++ {
		_, _, _, packet := s.build(nil, 100, now, 0)
		if packet == nil {
			t.Fatal("no stream progress")
		}
		for _, r := range packet.streams {
			if r.id == 0 {
				bulk += r.hi - r.lo
			} else {
				startup += r.hi - r.lo
			}
		}
		s.acked(packet)
		now = now.Add(time.Millisecond)
	}
	if bulk < 300 || startup < 300 {
		t.Fatalf("scheduler starved a traffic class: bulk=%d startup=%d", bulk, startup)
	}
}
func TestSilentInitialFlightsCannotBlockTheQueueForever(t *testing.T) {
	s := startupFixture(t, 4, 0)
	for _, st := range s.streams {
		st.sbuf = nil
		st.spending = nil
	}
	now := time.Now()
	s.startupOrder(now)
	if s.startupAllowed(s.streams[2]) {
		t.Fatal("too many initial slots")
	}
	s.startupOrder(now.Add(6 * time.Second))
	if !s.startupAllowed(s.streams[2]) {
		t.Fatal("silent peer permanently blocked queued streams")
	}
}
