"""ListKoro: FB/IG/YT link, poster ba text theke movie/tool list. `python app.py` diye chalao."""
import shutil, tempfile, threading, time, uuid
from pathlib import Path
from flask import Flask, jsonify, redirect, request, send_from_directory
from urllib.parse import urlencode
import ai, config, db, fetcher, local_pipeline as lp

app = Flask(__name__, static_folder="static", static_url_path="/static")
app.config["MAX_CONTENT_LENGTH"] = 40 * 1024 * 1024
JOBS = {}
IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".gif", ".bmp"}


# ------------------------------------------------------------------ job runner
def _set(job, **kw):
    JOBS[job].update(kw, updated=time.time())


def run_job(job, kind, payload, extra_text=""):
    work = Path(tempfile.mkdtemp(dir=config.TMP))
    step = lambda s: _set(job, step=s)
    try:
        provider = ai.get_provider()
        provider.step = step
        warning = ""
        if kind == "text":
            url = fetcher.find_url(payload)
            only_url = url and len(payload.strip()) <= len(url) + 40
            if only_url:          # text er bhitore shudhu link -> link hisebe dhoro
                kind, payload = "url", url
        if kind == "text":
            step("AI text porche")
            res, source = provider.from_text(payload), payload[:300]
        elif kind == "image":
            step("AI poster/image porche")
            res, source = provider.from_images(payload, extra_text), "image upload"
        else:
            media = fetcher.fetch(payload, work, step)
            source = payload
            if media["kind"] == "video":
                step("AI audio shunche" if media.get("audio_only") else "AI video dekhche")
                res = provider.from_video(media["paths"][0], media["caption"])
            elif media["kind"] == "image":
                step("AI poster porche")
                res = provider.from_images(media["paths"], media["caption"])
            else:
                step("AI caption porche")
                res = provider.from_text(media["caption"])
            if not res["title"]:
                res["title"] = (media.get("title") or "")[:80]
            warning = media.get("warning", "")
        if not res["items"]:
            raise ai.AIError("Kono movie/tool er nam pawa gelo na.")
        rid = db.add(kind, source, res["title"] or "Untitled list", res["items"], provider.name)
        result = db.get(rid)
        if warning:
            result["warning"] = warning
        _set(job, status="done", step="Done", result=result)
    except (ai.AIError, fetcher.FetchError) as e:
        _set(job, status="error", error=str(e))
    except Exception as e:  # noqa
        app.logger.exception("job failed")
        _set(job, status="error", error=f"{type(e).__name__}: {e}")
    finally:
        shutil.rmtree(work, ignore_errors=True)
        if kind == "image":
            for p in payload:
                Path(p).unlink(missing_ok=True)


def start_job(kind, payload, extra_text=""):
    job = uuid.uuid4().hex[:12]
    JOBS[job] = {"id": job, "status": "running", "step": "Shuru hocche", "created": time.time()}
    threading.Thread(target=run_job, args=(job, kind, payload, extra_text), daemon=True).start()
    for k in [k for k, v in JOBS.items() if time.time() - v["created"] > 3600]:
        JOBS.pop(k, None)
    return job


def _save_images(files):
    paths = []
    for f in files:
        if not f or not f.filename:
            continue
        ext = Path(f.filename).suffix.lower() or ".jpg"
        if ext not in IMG_EXT:
            continue
        p = config.TMP / f"up_{uuid.uuid4().hex}{ext}"
        f.save(p)
        paths.append(p)
    return paths


# ------------------------------------------------------------------ routes
@app.get("/")
def index():
    return send_from_directory("static", "index.html")


@app.get("/manifest.json")
def manifest():
    return send_from_directory("static", "manifest.json", mimetype="application/manifest+json")


@app.get("/sw.js")
def sw():
    return send_from_directory("static", "sw.js", mimetype="text/javascript")


@app.post("/api/jobs")
def create_job():
    if request.files:
        paths = _save_images(request.files.getlist("image"))
        if not paths:
            return jsonify(error="Image file dao (jpg/png/webp)"), 400
        return jsonify(job=start_job("image", paths, request.form.get("text", "")))
    data = request.get_json(silent=True) or {}
    url, text = (data.get("url") or "").strip(), (data.get("text") or "").strip()
    if url:
        found = fetcher.find_url(url)
        if not found:
            return jsonify(error="Valid link dao (https://...)"), 400
        return jsonify(job=start_job("url", found))
    if text:
        return jsonify(job=start_job("text", text))
    return jsonify(error="Link, text ba image dao"), 400


@app.get("/api/jobs/<job>")
def job_status(job):
    j = JOBS.get(job)
    return (jsonify(j), 200) if j else (jsonify(error="Job pawa jay nai"), 404)


@app.get("/api/history")
def history():
    return jsonify(db.search(request.args.get("q", "").strip()))


@app.delete("/api/history/<int:rid>")
def history_delete(rid):
    db.delete(rid)
    return jsonify(ok=True)


@app.get("/api/settings")
def settings_get():
    key = config.get("GEMINI_API_KEY")
    return jsonify(
        provider=config.get("PROVIDER"), gemini_model=config.get("GEMINI_MODEL"),
        gemini_key_set=bool(key), gemini_key_hint=(key[:4] + "…" + key[-3:]) if key else "",
        ollama_host=config.get("OLLAMA_HOST"), ollama_model=config.get("OLLAMA_MODEL"),
        ollama_vision_model=config.get("OLLAMA_VISION_MODEL"), tesseract_lang=config.get("TESSERACT_LANG"),
        cookies=config.COOKIES.exists(), ffmpeg=lp.have("ffmpeg"), tesseract=lp.have("tesseract"))


@app.get("/api/debug")
def debug():
    import platform, yt_dlp
    return jsonify(app="server", python=platform.python_version(), ytdl_ok=True, ytdl_version=yt_dlp.version.__version__,
                   ffmpeg=lp.have("ffmpeg"), tesseract=lp.have("tesseract"), provider=config.get("PROVIDER"),
                   gemini_model=config.get("GEMINI_MODEL"), cookies=config.COOKIES.exists())


@app.post("/api/settings")
def settings_set():
    data = request.get_json(silent=True) or {}
    m = {"provider": "PROVIDER", "gemini_key": "GEMINI_API_KEY", "gemini_model": "GEMINI_MODEL",
         "ollama_host": "OLLAMA_HOST", "ollama_model": "OLLAMA_MODEL",
         "ollama_vision_model": "OLLAMA_VISION_MODEL", "tesseract_lang": "TESSERACT_LANG"}
    config.save({env: data[k] for k, env in m.items() if k in data and (k != "gemini_key" or data[k])})
    return settings_get()


@app.post("/api/cookies")
def cookies_set():
    f = request.files.get("file")
    text = f.read().decode("utf-8", "replace") if f else (request.get_json(silent=True) or {}).get("text", "")
    if "\t" not in text:
        return jsonify(error="Eta Netscape cookies.txt format na. 'Get cookies.txt LOCALLY' extension diye export koro."), 400
    if not text.lstrip().startswith("#"):
        text = "# Netscape HTTP Cookie File\n" + text
    config.COOKIES.write_text(text, encoding="utf-8")
    return jsonify(ok=True)


@app.delete("/api/cookies")
def cookies_delete():
    config.COOKIES.unlink(missing_ok=True)
    return jsonify(ok=True)


# PWA share target: FB app -> Share -> ListKoro
@app.route("/share", methods=["GET", "POST"])
def share():
    src = request.form if request.method == "POST" else request.args
    text = " ".join(x for x in (src.get("title"), src.get("text"), src.get("url")) if x).strip()
    paths = _save_images(request.files.getlist("image")) if request.files else []
    if paths:
        return redirect("/?" + urlencode({"job": start_job("image", paths, text)}), 303)
    url = fetcher.find_url(text)
    if url:
        return redirect("/?" + urlencode({"job": start_job("url", url)}), 303)
    if text:
        return redirect("/?" + urlencode({"job": start_job("text", text)}), 303)
    return redirect("/", 303)


if __name__ == "__main__":
    host, port = config.get("HOST"), int(config.get("PORT"))
    print(f"\n  ListKoro chalu: http://{'localhost' if host in ('127.0.0.1', '0.0.0.0') else host}:{port}\n")
    app.run(host=host, port=port, threaded=True)
