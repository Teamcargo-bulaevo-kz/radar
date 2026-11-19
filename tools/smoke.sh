#!/usr/bin/env bash
# Дымовой тест в эмуляторе: ставим APK, запускаем, обходим вкладки, снимаем скриншоты, ищем падения.
set -u
PKG=kz.bulaevo.radar
OUT=smoke-out
mkdir -p "$OUT/shots"

adb install -r out/Radar.apk 2>&1 | tee "$OUT/install.log"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>&1 | tee -a "$OUT/install.log" || true
adb shell pm grant "$PKG" android.permission.CAMERA 2>&1 | tee -a "$OUT/install.log" || true
adb logcat -c

shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; }

adb shell am start -n "$PKG/.MainActivity"
sleep 8
shot 00_start

: > "$OUT/tabs.log"
i=1
if [ -f tools/tabs.txt ]; then
  while IFS= read -r tab || [ -n "$tab" ]; do
    [ -z "$tab" ] && continue
    n=$(printf '%02d' "$i")
    if python3 tools/ui_tap.py "$tab" >> "$OUT/tabs.log" 2>&1; then
      echo "$n tab '$tab': tapped" >> "$OUT/tabs.log"
    else
      echo "$n tab '$tab': TAP FAILED" >> "$OUT/tabs.log"
    fi
    sleep 6
    shot "${n}_tab"
    i=$((i + 1))
  done < tools/tabs.txt
fi

adb shell dumpsys activity processes 2>/dev/null | grep -i "kz.bulaevo.radar" | head -3 >> "$OUT/tabs.log"
adb logcat -d > "$OUT/logcat.txt"
adb logcat -d -s AndroidRuntime:E > "$OUT/crash.txt"

if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "CRASH DETECTED"
  grep -A 25 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60
  exit 1
fi
echo "No crash detected"
exit 0
