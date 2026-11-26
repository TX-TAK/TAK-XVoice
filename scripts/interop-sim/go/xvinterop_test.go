// Local-only interop harness (not part of openmanetd). Stage 1 encodes real
// 10 ms Opus frames the way TAK-XVoice's capture path does; stage 2 replays
// datagrams produced by XV's Kotlin wire codecs through the SAME functions the
// daemon's receive path calls: rtp.ParseIncomingInto -> JitterBuffer.PushWithSSRC
// -> PopReady -> Opus decode.
package xvinterop_test

import (
	"bufio"
	"encoding/hex"
	"fmt"
	"math"
	"os"
	"sort"
	"strings"
	"testing"
	"time"

	pionrtp "github.com/pion/rtp"

	"github.com/hraban/opus"
	"github.com/openmanet/openmanetd/internal/comms/codec"
	"github.com/openmanet/openmanetd/internal/comms/rtp"
)

const (
	bursts = 3
	// playoutSlice is the PCM slice the daemon decodes into per tick
	// (receive.go playoutOneFrame): one 20 ms period at 48 kHz mono.
	playoutSlice = 960
)

// 10 ms frames per PTT press. Odd counts on purpose: each leaves a stranded
// half-pair at release, exercising the codec's burst-boundary handling.
var burstFrames = [bursts]int{31, 29, 31}

func totalFrames() int {
	n := 0
	for _, f := range burstFrames {
		n += f
	}
	return n
}

func TestGenerateFrames(t *testing.T) {
	out := os.Getenv("XV_FRAMES_OUT")
	if out == "" {
		t.Skip("XV_FRAMES_OUT not set")
	}
	enc, err := opus.NewEncoder(48000, 1, opus.AppVoIP)
	if err != nil {
		t.Fatal(err)
	}
	_ = enc.SetBitrate(40000)
	f, _ := os.Create(out)
	defer f.Close()
	phase := 0.0
	for b := 0; b < bursts; b++ {
		for i := 0; i < burstFrames[b]; i++ {
			pcm := make([]int16, 480) // 10 ms @ 48 kHz, what XV's capture emits
			for k := range pcm {
				pcm[k] = int16(8000 * math.Sin(phase))
				phase += 2 * math.Pi * (300 + 40*float64(b)) / 48000
			}
			buf := make([]byte, 1275)
			n, err := enc.Encode(pcm, buf)
			if err != nil {
				t.Fatal(err)
			}
			fmt.Fprintf(f, "%d %s\n", b, hex.EncodeToString(buf[:n]))
		}
	}
}

type result struct {
	sent, parseErrs, pushed, rejected, popped int
	decodedSamples, decodeErrs                int
	full20ms                                  int // packets that filled the daemon's 960-sample slice
	peak                                      int16
	ssrcResets, idleResets                    int64
}

func replay(t *testing.T, lines []string) result {
	dec, err := codec.NewOpusDecoder(48000, 1)
	if err != nil {
		t.Fatal(err)
	}
	jb := rtp.NewJitterBuffer(rtp.PrebufferPackets, rtp.MaxDepth)
	var r result
	var pkt pionrtp.Packet
	pcm := make([]int16, playoutSlice) // same slice size the daemon decodes into
	lastBurst := -1
	for _, ln := range lines {
		var b int
		var h string
		fmt.Sscanf(ln, "%d %s", &b, &h)
		if lastBurst >= 0 && b != lastBurst {
			time.Sleep(300 * time.Millisecond) // rapid re-key: well under the 2 s idle reset
		}
		lastBurst = b
		raw, _ := hex.DecodeString(h)
		r.sent++
		if err := rtp.ParseIncomingInto(raw, &pkt); err != nil {
			r.parseErrs++
			continue
		}
		if jb.PushWithSSRC(pkt.SSRC, pkt.SequenceNumber, pkt.Payload, nil) {
			r.pushed++
		} else {
			r.rejected++
		}
		for {
			p, ready, _ := jb.PopReady()
			if !ready {
				break
			}
			r.popped++
			n, err := dec.DecodeS16(p, pcm)
			if err != nil {
				r.decodeErrs++
				continue
			}
			r.decodedSamples += n
			if n == playoutSlice {
				r.full20ms++
			}
			for _, s := range pcm[:n] {
				if s > r.peak {
					r.peak = s
				}
			}
		}
	}
	r.ssrcResets, r.idleResets = jb.SSRCResets.Load(), jb.IdleResets.Load()
	return r
}

func TestReplay(t *testing.T) {
	in := os.Getenv("XV_DGRAMS_IN")
	if in == "" {
		t.Skip("XV_DGRAMS_IN not set")
	}
	f, err := os.Open(in)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	byScn := map[string][]string{}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		parts := strings.SplitN(sc.Text(), " ", 2)
		byScn[parts[0]] = append(byScn[parts[0]], parts[1])
	}
	names := make([]string, 0, len(byScn))
	for n := range byScn {
		names = append(names, n)
	}
	sort.Strings(names)
	res := map[string]result{}
	for _, n := range names {
		r := replay(t, byScn[n])
		res[n] = r
		t.Logf("%-18s sent=%3d parseErr=%3d pushed=%3d rejected=%3d popped=%3d full20ms=%3d decodedMs=%4d decErr=%d peak=%5d ssrcRst=%d idleRst=%d",
			n, r.sent, r.parseErrs, r.pushed, r.rejected, r.popped, r.full20ms, r.decodedSamples/48, r.decodeErrs, r.peak, r.ssrcResets, r.idleResets)
	}

	// The fix: every datagram is RTP, accepted, in order, and decodes. Audio
	// loss is bounded by one stranded 10 ms half-pair per burst boundary.
	om, ok := res["openmanet_rtp"]
	if !ok {
		t.Fatal("no openmanet_rtp scenario in input")
	}
	totalMs := totalFrames() * 10
	gotMs := om.decodedSamples / 48
	if om.parseErrs != 0 || om.rejected != 0 || om.decodeErrs != 0 {
		t.Errorf("openmanet_rtp: parseErr=%d rejected=%d decErr=%d, want all 0", om.parseErrs, om.rejected, om.decodeErrs)
	}
	if om.pushed != om.sent || om.popped != om.sent {
		t.Errorf("openmanet_rtp: sent=%d pushed=%d popped=%d, want all equal", om.sent, om.pushed, om.popped)
	}
	if gotMs > totalMs || gotMs < totalMs-10*bursts {
		t.Errorf("openmanet_rtp: decoded %d ms, want within [%d, %d]", gotMs, totalMs-10*bursts, totalMs)
	}
	// Packing must really produce 20 ms packets: roughly half as many datagrams
	// as input frames, nearly all filling the daemon's playout slice. A codec
	// that stopped packing would still decode and pass the checks above while
	// the daemon zero-pads every tick.
	if om.sent > totalFrames()*6/10 {
		t.Errorf("openmanet_rtp: %d datagrams for %d frames; frames are not being packed into 20 ms", om.sent, totalFrames())
	}
	if om.full20ms*10 < om.sent*9 {
		t.Errorf("openmanet_rtp: only %d of %d packets are full 20 ms", om.full20ms, om.sent)
	}
	if om.peak < 2000 {
		t.Errorf("openmanet_rtp: decoded audio is silent (peak %d)", om.peak)
	}
	if om.idleResets != 0 || om.ssrcResets != 0 {
		t.Errorf("openmanet_rtp: unexpected jitter resets ssrc=%d idle=%d", om.ssrcResets, om.idleResets)
	}

	// Negative controls: the pre-fix behaviors must still reproduce, otherwise
	// the harness (or openmanetd) changed and the audit's claims need rechecking.
	if v := res["old_vx_raw_opus"]; v.decodedSamples != 0 {
		t.Errorf("old_vx_raw_opus: decoded %d ms, expected openmanetd to drop raw Opus", v.decodedSamples/48)
	}
	if n := res["old_perburst_seq"]; n.rejected == 0 {
		t.Errorf("old_perburst_seq: no rejected packets; per-burst seq reset no longer drops re-keys?")
	}
}
