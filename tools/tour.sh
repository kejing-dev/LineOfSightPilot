#!/usr/bin/env bash
# Screenshot tour (design states). Each line: "SECONDS|SHOT|CMD;CMD;..." -> run the commands, wait, capture SHOT.
#   tools/tour.sh OUTDIR < steps.txt        (SHOT "-" = no capture)
DEV=${DEV:-emulator-5556}
OUT="${1:?outdir}"; mkdir -p "$OUT"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
while IFS='|' read -r wait shot cmds; do
  [ -z "$wait" ] && continue
  case "$wait" in \#*) continue ;; esac
  IFS=';' read -ra list <<< "$cmds"
  for c in "${list[@]}"; do [ -n "$c" ] && "$ROOT/tools/q.sh" "$c" >> "$OUT/tour.txt" < /dev/null; done
  sleep "$wait"
  if [ "$shot" != "-" ]; then
    adb -s $DEV emu screenrecord screenshot "$OUT/$shot.png" >/dev/null 2>&1 </dev/null
    st=$(adb -s $DEV logcat -d -s DroneSight:V 2>/dev/null </dev/null | grep "STATE " | tail -1 | sed 's/.*STATE //' | cut -c1-80)
    echo "SHOT $shot state=$st" >> "$OUT/tour.txt"
  fi
done
echo done >> "$OUT/tour.txt"
