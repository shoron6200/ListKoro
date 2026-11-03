"""Link theke media ana: yt-dlp (video/reel) + og:image fallback (photo/poster post)."""
import html, re, subprocess
from http.cookiejar import MozillaCookieJar
from pathlib import Path
import requests
import yt_dlp
import config

URL_RE = re.compile(r"https?://[^\s<>\"']+")
BROWSER_UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
DESKTOP_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/130.0 Safari/537.36")
# Ei link gula video: fail hole poster/thumbnail e chole jabo na
VIDEO_RE = re.compile(r"facebook\.com/(reel|reels|watch|share/[vr])\b|facebook\.com/.+/videos?/|fb\.watch/|"
                      r"instagram\.com/(reel|reels|tv)/|youtube\.com/(watch|shorts)|youtu\.be/|tiktok\.com/", re.I)
SHORT_RE = re.compile(r"facebook\.com/share/|fb\.watch/|fb\.me/|l\.facebook\.com/", re.I)
LOGIN_HINT = " Private/login-only video hole Settings e cookies.txt dao."


class FetchError(Exception):
    pass


def find_url(text):
    m = URL_RE.search(text or "")
    return m.group(0).rstrip(").,") if m else None


def _short_err(e):
    msg = re.sub(r"\x1b\[[0-9;]*m", "", str(e)).split("; please report")[0]
    return msg.replace("ERROR: ", "").strip()[:220]


def is_video_url(url):
    return bool(VIDEO_RE.search(url or ""))


def _desktop(url):
    return re.sub(r"://(m|mbasic|web|touch)\.facebook\.com", "://www.facebook.com", url)


def resolve_url(url):
    """FB share/short link -> asol post URL (yt-dlp er jonno)."""
    u = _desktop(url)
    if not SHORT_RE.search(u):
        return u
    try:
        r = requests.get(u, headers={"User-Agent": DESKTOP_UA, "Accept-Language": "en"}, cookies=_cookiejar(),
                         timeout=20, allow_redirects=True, stream=True)
        final = r.url
        r.close()
        if final and "login" not in final and "checkpoint" not in final:
            return _desktop(final)
    except requests.RequestException:
        pass
    return u


def _cookie_opts():
    return {"cookiefile": str(config.COOKIES)} if config.COOKIES.exists() else {}


def _cookiejar():
    if not config.COOKIES.exists():
        return None
    jar = MozillaCookieJar(str(config.COOKIES))
    try:
        jar.load(ignore_discard=True, ignore_expires=True)
        return jar
    except Exception:
        return None


def _caption(info):
    parts = [info.get("title") or "", info.get("description") or ""]
    seen, out = set(), []
    for p in parts:
        p = p.strip()
        if p and p not in seen:
            seen.add(p)
            out.append(p)
    return "\n".join(out)[:6000]


def _ydl_base(progress=None):
    # User-Agent dei na: yt-dlp nijer desktop UA dey, FB extractor sei page e kaj kore
    opts = {"quiet": True, "no_warnings": True, "noplaylist": False, "socket_timeout": 30, **_cookie_opts()}
    if progress:
        def hook(d):
            if d.get("status") == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                if total:
                    progress(f"Downloading {d.get('downloaded_bytes', 0) * 100 // total}%")
        opts["progress_hooks"] = [hook]
    return opts


def _og_fallback(url, workdir):
    """Photo/poster post: page er og:image + og:description."""
    attempts = [({"User-Agent": "facebookexternalhit/1.1"}, None),       # public post e og tag dey
                ({"User-Agent": BROWSER_UA, "Accept-Language": "en"}, _cookiejar())]  # private
    for headers, jar in attempts:
        try:
            r = requests.get(url, headers=headers, cookies=jar, timeout=30, allow_redirects=True)
        except requests.RequestException:
            continue
        if not r.ok:
            continue
        meta = {}
        for m in re.finditer(r'<meta[^>]+(?:property|name)="(og:[a-z:]+)"[^>]+content="([^"]*)"', r.text):
            meta.setdefault(m.group(1), html.unescape(m.group(2)))
        img = meta.get("og:image")
        caption = "\n".join(x for x in (meta.get("og:title"), meta.get("og:description")) if x)
        if img and "login" not in r.url:
            try:
                ir = requests.get(img, headers={"User-Agent": BROWSER_UA}, timeout=60)
                ir.raise_for_status()
                ext = ".png" if "png" in ir.headers.get("content-type", "") else ".jpg"
                p = Path(workdir) / f"poster{ext}"
                p.write_bytes(ir.content)
                return {"kind": "image", "paths": [p], "caption": caption, "title": meta.get("og:title", "")}
            except requests.RequestException:
                pass
        if caption and "login" not in r.url:
            return {"kind": "text", "paths": [], "caption": caption, "title": meta.get("og:title", "")}
    return None


def fetch(url, workdir, progress=lambda s: None):
    """Return {kind: video|image|text, paths, caption, title}."""
    workdir = Path(workdir)
    progress("Link check hocche")
    original = url
    url = resolve_url(url)
    video_link = is_video_url(original) or is_video_url(url)
    progress("Post info ana hocche")
    info, err = None, ""
    for u in dict.fromkeys([url, original]):  # resolved age, na hole original
        try:
            with yt_dlp.YoutubeDL(_ydl_base()) as y:
                info = y.extract_info(u, download=False)
            url = u
            break
        except yt_dlp.utils.DownloadError as e:
            err = _short_err(e)

    hint = LOGIN_HINT if not config.COOKIES.exists() else " Cookies expire hoye thakte pare, notun kore dao."
    if info is None:
        if video_link:  # video link e thumbnail porle bhul list ashe, tai clear error
            raise FetchError("Video download hoy nai." + hint + " (" + err + ")")
        progress("Video pawa jay nai, poster/image khujchi")
        res = _og_fallback(url, workdir)
        if res:
            res["warning"] = "Video pawa jay nai, post er image/caption theke pora hoyeche."
            return res
        raise FetchError("Link theke kichu ana gelo na." + hint + " Na hole screenshot upload koro. (" + err + ")")

    entries = [e for e in (info.get("entries") or [info]) if e]
    first = entries[0]
    caption = _caption(info) or _caption(first)
    duration = first.get("duration") or 0
    has_video = any(f.get("vcodec") not in (None, "none") for f in first.get("formats") or []) or first.get("vcodec") not in (None, "none")

    long_video = duration and duration > float(config.get("MAX_VIDEO_MIN")) * 60
    fmt = "ba/b" if (long_video or not has_video) else \
        "b[height<=360]/bv*[height<=360]+ba/b[height<=480]/wv*+ba/w/b"
    opts = {**_ydl_base(progress), "format": fmt, "outtmpl": str(workdir / "media_%(autonumber)s.%(ext)s"),
            "merge_output_format": "mp4", "playlist_items": "1"}
    progress("Audio download hocche (long video)" if long_video else "360p download hocche")
    try:
        with yt_dlp.YoutubeDL(opts) as y:
            y.download([first.get("webpage_url") or url])
    except yt_dlp.utils.DownloadError as e:
        if caption:
            return {"kind": "text", "paths": [], "caption": caption, "title": info.get("title", ""),
                    "warning": "Video download hoy nai (" + _short_err(e)[:120] + "), shudhu caption theke pora."}
        raise FetchError("Download fail." + hint + " (" + _short_err(e) + ")")
    files = sorted(p for p in workdir.glob("media_*") if p.suffix not in (".part", ".ytdl"))
    if not files:
        raise FetchError("Download hoy nai")
    if long_video and files[0].suffix.lower() not in (".mp3", ".wav"):
        mp3 = workdir / "audio.mp3"  # AI er jonno choto, mono mp3
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(files[0]), "-vn", "-ac", "1",
                        "-b:a", "48k", str(mp3)], capture_output=True)
        if mp3.exists():
            files = [mp3]
    return {"kind": "video", "paths": files[:1], "caption": caption, "title": info.get("title", ""),
            "audio_only": bool(long_video)}
