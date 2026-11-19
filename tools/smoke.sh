#!/usr/bin/env bash
# Дымовой тест в эмуляторе: ставим APK, запускаем, обходим вкладки (параметры запуска), снимаем скриншоты, ищем падения.
set -u
PKG=kz.bulaevo.radar
OUT=smoke-out
mkdir -p "$OUT/shots"

adb install -r out/Radar.apk 2>&1 | tee "$OUT/install.log"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>&1 | tee -a "$OUT/install.log" || true
adb shell pm grant "$PKG" android.permission.CAMERA 2>&1 | tee -a "$OUT/install.log" || true
adb logcat -c

shot() { adb exec-out screencap -p > "$OUT/shots/$1.png"; }
launch() { adb shell am start -n "$PKG/.MainActivity" "$@" >/dev/null 2>&1; }

launch
sleep 8
shot 00_start

: > "$OUT/tabs.log"
i=0
if [ -f tools/tabs.txt ]; then
  while IFS= read -r tab || [ -n "$tab" ]; do
    [ -z "$tab" ] && continue
    n=$(printf '%02d' "$((i + 1))")
    launch --ei tab "$i" --ez start true
    echo "$n tab '$tab' (index $i): opened" >> "$OUT/tabs.log"
    sleep 9
    shot "${n}_tab"
    i=$((i + 1))
  done < tools/tabs.txt
fi

# Сервис: самотест, сведения об устройстве, подбор диапазона
last=$((i - 1))
for act in selftest info calibrate; do
  launch --ei tab "$last" --es act "$act"
  sleep 14
  shot "svc_$act"
  echo "service '$act': done" >> "$OUT/tabs.log"
done

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
