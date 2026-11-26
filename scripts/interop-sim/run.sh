#!/usr/bin/env bash
# Local OpenMANET interop simulation. No radios, no nodes, no ATAK SDK.
#
#   1. Fetch the latest openmanetd `main` (public repo) into .tmp/openmanetd.
#   2. Encode real 10 ms Opus frames (libopus, as XV's capture path does).
#   3. Run them through XV's actual Kotlin wire codecs (the new OPENMANET_RTP
#      codec plus the two pre-fix behaviors as negative controls).
#   4. Replay the resulting datagrams through the SAME functions openmanetd's
#      receive path calls: rtp.ParseIncomingInto -> JitterBuffer ->
#      PopReady -> Opus decode, and assert on the outcome.
#
# This validates wire-level compatibility (RTP framing, PT, 20 ms packets,
# sequence handling across rapid re-keys, decodability). It does NOT exercise
# radios, batman-adv, multicast routing/TTL, DSCP, ALSA/audio devices or the
# daemon's talkgroup/config layer. On-air verification is still required.
#
# Requirements: JDK 17+, Go (toolchain auto-downloads), git, gcc,
# libopus + pkg-config (apt: libopus-dev libopusfile-dev pkg-config;
# brew: opus opusfile pkg-config). pwsh is the repo's usual shell, but this
# script is bash so it runs unmodified in CI-like Linux/WSL environments.
#
# Env:
#   OPENMANETD_DIR  use this checkout instead of the managed .tmp clone
#                   (never fetched or modified; the test file is copied in
#                   and removed on exit)
#   OPENMANETD_REF  branch/tag/commit to test (default: main)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
work="$repo/.tmp/interop-sim"
mkdir -p "$work"

for tool in go git gcc pkg-config; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 2; }
done
pkg-config --exists opus || { echo "libopus dev files not found (see header for install hints)" >&2; exit 2; }

ref="${OPENMANETD_REF:-main}"
if [[ -n "${OPENMANETD_DIR:-}" ]]; then
  daemon="$OPENMANETD_DIR"
else
  daemon="$repo/.tmp/openmanetd"
  if [[ ! -d "$daemon/.git" ]]; then
    GIT_LFS_SKIP_SMUDGE=1 git clone --quiet --depth 1 --branch "$ref" https://github.com/OpenMANET/openmanetd "$daemon"
  else
    git -C "$daemon" fetch --quiet --depth 1 origin "$ref"
    git -C "$daemon" reset --quiet --hard FETCH_HEAD
  fi
fi
echo "openmanetd: $(git -C "$daemon" rev-parse --short HEAD) ($ref)"

dest="$daemon/internal/comms/xvinterop"
mkdir -p "$dest"
cp "$here/go/xvinterop_test.go" "$dest/xvinterop_test.go"
trap 'rm -rf "$dest"' EXIT

frames="$work/frames.txt"
dgrams="$work/dgrams.txt"

echo "== 1/3 encode real 10 ms Opus frames"
(cd "$daemon" && XV_FRAMES_OUT="$frames" go test ./internal/comms/xvinterop/ -run TestGenerateFrames -count=1)

echo "== 2/3 frame them with XV's wire codecs"
"$repo/gradlew" -q -p "$here" run --args="$frames $dgrams"

echo "== 3/3 replay through openmanetd's receive path"
(cd "$daemon" && XV_DGRAMS_IN="$dgrams" go test ./internal/comms/xvinterop/ -run TestReplay -count=1 -v)
