#!/usr/bin/env bash
# Send debug commands to the running app on emulator-5556 and wait (up to 30 s each) until each one is logged.
#   tools/q.sh 'lesson L1' 'fly hover' ...
DEV=${DEV:-emulator-5556}
PKG=io.embodit.dronesight
for c in "$@"; do
  n0=$(adb -s $DEV logcat -d -s DroneSight:V 2>/dev/null </dev/null | grep -c "CMD recv")
  adb -s $DEV shell -n "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$c'" >/dev/null 2>&1
  for i in $(seq 1 30); do
    n=$(adb -s $DEV logcat -d -s DroneSight:V 2>/dev/null </dev/null | grep -c "CMD recv")
    [ "$n" -gt "$n0" ] && break
    sleep 1
  done
  sleep 1
  adb -s $DEV logcat -d -s DroneSight:V 2>/dev/null </dev/null | grep "CMD " | grep -v "CMD recv" | tail -1 | cut -c1-400
done
