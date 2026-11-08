"""ListKoro: FB/IG/YT link, poster ba text theke movie/tool list. `python app.py` diye chalao."""
import shutil, tempfile, threading, time, uuid
from pathlib import Path
from flask import Flask, jsonify, redirect, request, send_file, send_from_directory
from urllib.parse import urlencode
import ai, config, db, fetcher, local_pipeline as lp

app = Flask(__name__, static_folder="static", static_url_path="/static")
app.config["MAX_CONTENT_LENGTH"] = 40 * 1024 * 1024
JOBS = {}
IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".gif", ".bmp"}


# ------------------------------------------------------------------ job runner
# Job: {id, mode: list|formats|download, status: running|done|error|cancelled, step, log[], progress, result}
PRIVATE = {"cancel"}


def _public(j):
    return {k: v for k, v in j.items() if k not in PRIVATE}


def _set(job, **kw):
    JOBS[job].update(kw, updated=time.time())


def _ctl(job):
    """step(msg): log e jog kore, cancel hole thamay. prog(d): download progress."""
    def check():
        if JOBS[job].get("cancel"):
            raise fetcher.Cancelled()

    def step(msg):
        check()
        j = JOBS[job]
        if j.get("step") != msg:
            j["log"].append({"t": round(time.time() - j["created"], 1), "msg": msg})
        _set(job, step=msg, progress=None)

    def prog(d):
        if JOBS[job].get("cancel"):
            import yt_dlp
            raise yt_dlp.utils.DownloadCancelled()
        _set(job, progress=d)
    return step, prog, check


def _finish(job, **kw):
    j = JOBS[job]
    if j.get("cancel"):
        kw = {"status": "cancelled", "error": "Cancel kora hoyeche"}
    msg = {"done": "Done ✓", "cancelled": "Cancel hoyeche", "error": "Error"}[kw["status"]]
    j["log"].append({"t": round(time.time() - j["created"], 1), "msg": msg})
    _set(job, step=msg, progress=None, **kw)


def _run(job, fn):
    work = Path(tempfile.mkdtemp(dir=config.TMP))
    try:
        _finish(job, status="done", result=fn(work))
    except fetcher.Cancelled:
        _finish(job, status="cancelled", error="Cancel kora hoyeche")
    except (ai.AIError, fetcher.FetchError) as e:
        _finish(job, status="error", error=str(e))
    except Exception as e:  # noqa
        app.logger.exception("job failed")
        _finish(job, status="error", error=f"{type(e).__name__}: {e}")
    finally:
        shutil.rmtree(work, ignore_errors=True)


def list_task(job, kind, payload, extra_text=""):
    step, prog, check = _ctl(job)

    def fn(work):
        nonlocal kind, payload
        try:
            provider = ai.get_provider()
            provider.step = step
            warning = ""
            if kind == "text":
                url = fetcher.find_url(payload)
                if url and len(payload.strip()) <= len(url) + 40:   # text e shudhu link -> link
                    kind, payload = "url", url
            if kind == "text":
                step("AI text porche")
                res, source = provider.from_text(payload), payload[:300]
            elif kind == "image":
                step("AI poster/image porche")
                res, source = provider.from_images(payload, extra_text), "image upload"
            else:
                media = fetcher.fetch(payload, work, step, prog)
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
            check()  # cancel hole result save hobe na
            if not res["items"]:
                raise ai.AIError("Kono movie/tool er nam pawa gelo na.")
            rid = db.add(kind, source, res["title"] or "Untitled list", res["items"], provider.name)
            result = db.get(rid)
            if warning:
                result["warning"] = warning
            return result
        finally:
            if kind == "image":
                for p in payload:
                    Path(p).unlink(missing_ok=True)
    _run(job, fn)


def formats_task(job, url):
    step, _, _ = _ctl(job)
    _run(job, lambda work: fetcher.formats(url, step))


def _save_dir():
    termux = Path.home() / "storage" / "downloads"   # Termux: phone er Download folder
    d = termux / "ListKoro" if termux.exists() else config.DATA / "downloads"
    d.mkdir(parents=True, exist_ok=True)
    return d


def download_task(job, url, fmt, kind, label):
    step, prog, check = _ctl(job)

    def fn(work):
        path, title = fetcher.download(url, fmt, kind, work, step, prog)
        check()
        step("Save hocche")
        dest = _save_dir() / path.name
        if dest.exists():
            dest = dest.with_name(f"{dest.stem}-{uuid.uuid4().hex[:4]}{dest.suffix}")
        shutil.move(str(path), dest)
        mime = "audio/mpeg" if dest.suffix == ".mp3" else "video/mp4"
        did = db.add_download(url, title, label, kind, dest.name, str(dest), dest.stat().st_size, mime)
        return _dl_public(db.get_download(did))
    _run(job, fn)


def _dl_public(d):
    d = dict(d)
    d["location"] = str(Path(d.pop("path", "")).parent).replace(str(Path.home()), "~")
    d["file_url"] = f"/api/files/{d['id']}"
    return d


def start_job(mode, target, *args, title="", **meta):
    job = uuid.uuid4().hex[:12]
    JOBS[job] = {"id": job, "mode": mode, "status": "running", "step": "Shuru hocche", "created": time.time(),
                 "log": [], "progress": None, "title": title, **meta}
    threading.Thread(target=target, args=(job, *args), daemon=True).start()
    for k in [k for k, v in JOBS.items() if time.time() - v["created"] > 6 * 3600 and v["status"] != "running"]:
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
        return jsonify(job=start_job("list", list_task, "image", paths, request.form.get("text", ""),
                                     title="Poster"))
    data = request.get_json(silent=True) or {}
    mode = data.get("mode") or "list"
    url, text = (data.get("url") or "").strip(), (data.get("text") or "").strip()
    found = fetcher.find_url(url) if url else None
    if url and not found:
        return jsonify(error="Valid link dao (https://...)"), 400
    if mode == "formats" and found:
        return jsonify(job=start_job("formats", formats_task, found, title=found))
    if mode == "download" and found:
        fmt, kind = data.get("format") or "", data.get("kind") or "video"
        if not fetcher.FORMAT_OK.match(fmt) or kind not in ("video", "audio"):
            return jsonify(error="Quality select koro"), 400
        label = (data.get("label") or "")[:20]
        return jsonify(job=start_job("download", download_task, found, fmt, kind, label,
                                     title=(data.get("title") or found)[:120], label=label, kind=kind))
    if found:
        return jsonify(job=start_job("list", list_task, "url", found, title=found))
    if text:
        return jsonify(job=start_job("list", list_task, "text", text, title=text[:60]))
    return jsonify(error="Link, text ba image dao"), 400


@app.get("/api/jobs")
def jobs_list():
    rows = sorted(JOBS.values(), key=lambda j: j["created"], reverse=True)
    if request.args.get("active"):
        rows = [j for j in rows if j["status"] == "running"]
    return jsonify([_public(j) for j in rows[:50]])


@app.get("/api/jobs/<job>")
def job_status(job):
    j = JOBS.get(job)
    return (jsonify(_public(j)), 200) if j else (jsonify(error="Job pawa jay nai"), 404)


@app.post("/api/jobs/<job>/cancel")
def job_cancel(job):
    j = JOBS.get(job)
    if not j:
        return jsonify(error="Job pawa jay nai"), 404
    if j["status"] == "running":
        j["cancel"] = True
        _set(job, step="Cancel hocche…")
    return jsonify(_public(j))


@app.get("/api/history")
def history():
    return jsonify(db.search(request.args.get("q", "").strip()))


@app.delete("/api/history/<int:rid>")
def history_delete(rid):
    db.delete(rid)
    return jsonify(ok=True)


@app.delete("/api/history")
def history_clear():
    db.clear()
    return jsonify(ok=True)


@app.get("/api/downloads")
def downloads():
    return jsonify([_dl_public(d) for d in db.list_downloads()])


@app.delete("/api/downloads/<int:did>")
def download_delete(did):
    d = db.get_download(did)
    if d:
        if request.args.get("file") == "1":
            Path(d["path"]).unlink(missing_ok=True)
        db.delete_download(did)
    return jsonify(ok=True)


@app.get("/api/files/<int:did>")
def download_file(did):
    d = db.get_download(did)
    if not d or not Path(d["path"]).exists():
        return jsonify(error="File nai"), 404
    return send_file(d["path"], mimetype=d["mime"], as_attachment=request.args.get("dl") == "1",
                     download_name=d["filename"])


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
        return redirect("/?" + urlencode({"job": start_job("list", list_task, "image", paths, text, title="Poster")}), 303)
    url = fetcher.find_url(text)
    if url:  # link: UI jiggesh korbe "List ber koro" naki "Download"
        return redirect("/?" + urlencode({"share": url}), 303)
    if text:
        return redirect("/?" + urlencode({"job": start_job("list", list_task, "text", text, title=text[:60])}), 303)
    return redirect("/", 303)


if __name__ == "__main__":
    host, port = config.get("HOST"), int(config.get("PORT"))
    print(f"\n  ListKoro chalu: http://{'localhost' if host in ('127.0.0.1', '0.0.0.0') else host}:{port}\n")
    app.run(host=host, port=port, threaded=True)
