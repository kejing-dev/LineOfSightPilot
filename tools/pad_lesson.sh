#!/usr/bin/env bash
# Flies lesson L1 end to end with the VIRTUAL STICK PADS only (debug `vstick` writes the same VirtualSticks the pads
# write): take off by holding the left pad up 1 s, climb into the box, hold 20 s (GPS), sink slowly onto the pad.
# Needs the app in S03/S04. Logs: OUTDIR/pad_lesson.txt
DEV=${DEV:-emulator-5556}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/.scratch}"; mkdir -p "$OUT"; LOG="$OUT/pad_lesson.txt"; : > "$LOG"
q() { "$ROOT/tools/q.sh" "$@" >> "$LOG" < /dev/null; }
logs() { adb -s $DEV logcat -d -s DroneSight:V < /dev/null 2>/dev/null; }
height() { logs | grep "DS-FLIGHT" | tail -1 | sed -E 's/.*pos=\(([-0-9.]+),([-0-9.]+),.*/\2/'; }
waitfor() { for i in $(seq 1 "$2"); do logs | grep -q "$1" && return 0; sleep 2; done; return 1; }
SPEED=${SPEED:-10}
q 'place' 'lesson L1' "speed $SPEED"
if [ "${PAD_TAKEOFF:-1}" = 1 ]; then
  q 'vstick l 0,0.9 20000'
  waitfor "TAKEOFF by stick" 30 && echo "takeoff by left pad: ok" >> "$LOG"
else
  q 'takeoff'
fi
waitfor "tookOff" 40 && echo "S06 reached" >> "$LOG"
q 'vstick off'
sleep 4
q 'vstick l 0,0.6 60000'
for i in $(seq 1 60); do h=$(height); awk "BEGIN{exit !($h >= 1.1)}" 2>/dev/null && break; sleep 1; done
q 'vstick off'
echo "released at h=$(height)" >> "$LOG"
waitfor "hint=DONE" 150 && echo "box goal reached (20 s in the box)" >> "$LOG"
q 'vstick l 0,-0.35 60000'
waitfor "RESULT lesson=L1" 120
logs | grep -E "RESULT lesson=L1|SESSION ev=.*Landed|TAKEOFF" | tail -4 >> "$LOG"
q 'vstick off'
echo done >> "$LOG"
