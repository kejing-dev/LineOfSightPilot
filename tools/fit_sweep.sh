#!/usr/bin/env bash
# D13 pre-QA gate (prompts/build.md): every state in both languages with the widest dynamic chips (two-digit hover
# timer, 360°, gate 4/4, round 12 with a timer past ten minutes), then prints the OVERFLOW lines (expect none).
#   tools/fit_sweep.sh [LANGS] [WAIT_S]     default "en zh", 5 s per step (the emulator runs at ~1 fps)
cd "$(dirname "$0")/.."
LANGS="${1:-en zh}"; W="${2:-5}"
c() { CMD_WAIT=0.3 tools/dev.sh cmd "$1" >/dev/null; sleep "${3:-$W}"; echo "  $1 -> $(tools/dev.sh log 'STATE' 1 | sed 's/.*STATE //' | cut -c1-60)"; }
tools/dev.sh cmd 'fit reset' >/dev/null
for L in $LANGS; do
  echo "== $L"
  c "lang $L"; c firstRun; c "nocontroller 0"; c "nocontroller 1"; c welcomeOk 8
  c "nofloor 1" 2; c "state S03" 8; c place 8; c "best L1 20"; c "best L3 270"
  c "lesson L1"; c "fly hover" 8; c "simulate 15" 6; c pause; c resume; c "bigtag 1"; c "bigtag 0"; c "fly land soft" 10
  c "lesson L2"; c "fly nosein" 8; c "simulate 15" 6; c pause; c resume
  c "fly crash" 10; c "lesson L3"; c "fly spin 4000" 10; c "simulate 20" 6
  c "fly crash" 10; c "lesson L4"; c "fly gate 4" 15; c pause; c resume
  c "fly crash" 10; c "lesson L5"; c "fly hover" 8; c "fly land off" 12
  c "lesson L6"; c "fly chain 3" 12; c "simulate 15" 6; c pause; c resume
  c "fly crash" 10; c "lesson L7"; c "fly hover" 8; c "simulate 15" 6
  c "fly crash" 10; c "lesson F1"; c "fly hover" 8; c "fly out" 8
  c "fly crash" 10; c "round 12"; c "lesson F2"; c takeoff 8; c "fly gate 3" 15; c "simulate 610" 12; c pause; c resume; c "fly crash" 10
  c "lesson L1"; c "fly crash" 10
  c settings 6; c "lang zh" 5; c "lang en" 5; c "lang $L" 5; c "settings bottom" 6; c resetAsk; c closeSettings; c quitAsk; c quitCancel; c block 6; c unblock 10
  echo "  $(CMD_WAIT=1 tools/dev.sh cmd fit)"
done
echo "== OVERFLOW lines"; tools/dev.sh log 'OVERFLOW' 200
