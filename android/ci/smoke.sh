#!/usr/bin/env bash
# Emulator e ListKoro smoke test: app install -> yt-dlp init -> local video link download -> list.
# Usage: smoke.sh <apk> <out-dir>
set -u
APK="$1"; OUT="${2:-smoke-out}"; mkdir -p "$OUT"
HERE="$(cd "$(dirname "$0")" && pwd)"
PORT=17865
fail=0
note(){ echo "== $*"; }
api(){ curl -s -m 30 "$@"; }
jget(){ python3 -c "import json,sys;d=json.load(sys.stdin);print($1)"; }

# host e chhoto file server; emulator theke 10.0.2.2 = host
(cd "$HERE" && python3 -m http.server 8077 >/dev/null 2>&1 &)

adb logcat -c || true
note "install"; adb install -r -g "$APK" || { echo "::error::install failed"; exit 1; }
adb shell am start -W -n com.listkoro.app/.MainActivity
sleep 10
adb forward tcp:$PORT tcp:$PORT

note "wait for yt-dlp init"
d=""
for i in $(seq 1 80); do
  d=$(api localhost:$PORT/api/debug || true)
  echo "$d" | grep -q '"ytdl_ready":true' && break
  sleep 3
done
echo "$d" | tee "$OUT/debug.json"; echo
if echo "$d" | grep -q '"ytdl_ok":true'; then
  note "yt-dlp OK: $(echo "$d" | jget "d.get('ytdl_version')")"
else
  echo "::error::yt-dlp init failed: $(echo "$d" | jget "d.get('ytdl_error')" 2>/dev/null)"
  fail=1
fi

run_job(){ # $1 = url, $2 = label
  local J r s last="" steps="$OUT/steps-$2.txt"; : > "$steps"
  J=$(api -H 'content-type: application/json; charset=utf-8' -d "{\"url\":\"$1\"}" localhost:$PORT/api/jobs | jget "d['job']")
  for i in $(seq 1 120); do
    r=$(api localhost:$PORT/api/jobs/$J)
    s=$(echo "$r" | jget "d['status']+' | '+str(d.get('step'))+' | '+str(d.get('error') or '')")
    [ "$s" != "$last" ] && echo "[$2] $s" | tee -a "$steps"
    last="$s"
    case "$s" in done*|error*) break;; esac
    sleep 3
  done
  echo "$r" > "$OUT/job-$2.json"
}

if [ $fail = 0 ]; then
  api -H 'content-type: application/json' -d '{"provider":"basic"}' localhost:$PORT/api/settings >/dev/null
  note "job: local mp4 (must download)"
  run_job "http://10.0.2.2:8077/sample.mp4" local
  if grep -q "AI video dekhche" "$OUT/steps-local.txt"; then
    note "download OK"
    grep -q "^\[local\] done" "$OUT/steps-local.txt" && note "OCR result: $(jget "[x['name'] for x in d['result']['items']]" < "$OUT/job-local.json")"
  else
    echo "::error::video download failed: $(tail -1 "$OUT/steps-local.txt")"; fail=1
  fi
  note "job: YouTube (info only, datacenter IP e block hote pare)"
  run_job "https://www.youtube.com/watch?v=jNQXAC9IVRw" youtube || true
fi

adb logcat -d > "$OUT/logcat.txt" || true
grep -iE "ListKoro|youtubedl|AndroidRuntime|FATAL" "$OUT/logcat.txt" | tail -60 || true
exit $fail
