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
python3 "$HERE/serve.py" "$HERE" </dev/null >/dev/null 2>&1 &
HTTP_PID=$!

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
  # step log e "AI video dekhche" thaka mane download hoyeche (emulator e OCR model na thakle porer step fail korte pare)
  if jget "any('AI video dekhche' in l['msg'] for l in d.get('log',[]))" < "$OUT/job-local.json" | grep -q True; then
    note "download OK"
    grep -q "^\[local\] done" "$OUT/steps-local.txt" && note "OCR result: $(jget "[x['name'] for x in d['result']['items']]" < "$OUT/job-local.json")"
  else
    echo "::error::video download failed: $(tail -1 "$OUT/steps-local.txt")"; fail=1
  fi
  post(){ api -H 'content-type: application/json; charset=utf-8' -d "$2" "localhost:$PORT$1"; }
  wait_job(){ local r; for i in $(seq 1 100); do r=$(api localhost:$PORT/api/jobs/$1); echo "$r" | grep -q '"status":"running"' || break; sleep 2; done; echo "$r"; }

  note "formats (quality list)"
  J=$(post /api/jobs '{"mode":"formats","url":"http://10.0.2.2:8077/sample.mp4"}' | jget "d['job']")
  R=$(wait_job $J); echo "$R" > "$OUT/formats.json"
  FMT=$(echo "$R" | jget "d['result']['options'][0]['format']" 2>/dev/null)
  if [ -n "$FMT" ]; then note "options: $(echo "$R" | jget "[o['label'] for o in d['result']['options']]")"; else echo "::error::formats failed: $(echo "$R" | jget "d.get('error')")"; fail=1; fi

  note "download -> phone storage"
  J=$(post /api/jobs "{\"mode\":\"download\",\"url\":\"http://10.0.2.2:8077/sample.mp4\",\"format\":\"$FMT\",\"kind\":\"video\",\"label\":\"Best\",\"title\":\"ListKoro test\"}" | jget "d['job']")
  R=$(wait_job $J); echo "$R" > "$OUT/download.json"
  if echo "$R" | grep -q '"status":"done"'; then note "saved: $(echo "$R" | jget "d['result']['uri']+'  '+d['result']['location']+'  '+str(d['result']['size'])")"
  else echo "::error::download failed: $(echo "$R" | jget "d.get('error')")"; fail=1; fi

  note "download cancel (slow file)"
  J=$(post /api/jobs '{"mode":"download","url":"http://10.0.2.2:8077/slow/sample.mp4","format":"b","kind":"video","label":"Best","title":"cancel test"}' | jget "d['job']")
  for i in $(seq 1 30); do r=$(api localhost:$PORT/api/jobs/$J); echo "$r" | grep -q '"pct"' && break; sleep 1; done
  note "progress: $(echo "$r" | jget "d.get('progress')")"
  post /api/jobs/$J/cancel '{}' >/dev/null
  R=$(wait_job $J)
  if echo "$R" | grep -q '"status":"cancelled"'; then note "cancel OK"; else echo "::error::cancel failed: $(echo "$R" | jget "d['status']+' '+str(d.get('error'))")"; fail=1; fi

  note "downloads list: $(api localhost:$PORT/api/downloads | jget "len(d)")"
  api -X DELETE localhost:$PORT/api/history >/dev/null
  H=$(api localhost:$PORT/api/history | jget "len(d)")
  if [ "$H" = "0" ]; then note "history clear OK"; else echo "::error::history clear failed ($H)"; fail=1; fi

  note "job: YouTube (info only, datacenter IP e block hote pare)"
  run_job "https://www.youtube.com/watch?v=jNQXAC9IVRw" youtube || true
fi

adb logcat -d > "$OUT/logcat.txt" || true
kill $HTTP_PID 2>/dev/null || true
grep -iE "ListKoro|youtubedl|AndroidRuntime|FATAL" "$OUT/logcat.txt" | tail -60 || true
exit $fail
