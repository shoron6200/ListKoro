"""AI providers: gemini (free API), ollama (fully local), basic (AI chara shudhu OCR lines)."""
import base64, json, mimetypes, re, time
from pathlib import Path
import requests
import config
import local_pipeline as lp

PROMPT = """You extract lists of named things from social-media content (Facebook/Instagram/YouTube reels, posts, posters, captions).
Such content usually recommends MOVIES, SERIES, ANIME, AI TOOLS, APPS, WEBSITES, BOOKS etc. Language may be Bangla, Hindi, English or mixed.
Look at EVERYTHING: spoken words, on-screen text, posters, logos, and the caption/description.

Return ONLY valid JSON in this exact shape:
{"title": "short title for this list (e.g. 'Top 10 Thriller Movies' or 'AI Video Tools')",
 "items": [{"name": "official name", "type": "movie|series|anime|tool|app|website|book|other",
            "year": "release year if known else empty", "note": "max 12 words: what it is / why recommended",
            "timestamp": "mm:ss where it appears in the video, else empty"}]}

Rules:
- Include every distinct recommended item, in the order they appear. No duplicates.
- Fix OCR / speech spelling mistakes to the real official name (e.g. 'lnterstel1ar' -> 'Interstellar', 'chat gpt' -> 'ChatGPT').
- Do NOT invent items that are not in the content. Ignore the creator's own name, page names, hashtags, 'like & follow' text.
- If nothing is found, return {"title": "", "items": []}."""

TYPES = {"movie", "series", "anime", "tool", "app", "website", "book", "other"}


class AIError(Exception):
    pass


def parse_json(text):
    """LLM output theke JSON ber kora, ```json fence / extra text thakleo."""
    if isinstance(text, (dict, list)):
        data = text
    else:
        t = re.sub(r"^```(?:json)?|```$", "", text.strip(), flags=re.M).strip()
        data = None
        pairs = sorted((("{", "}"), ("[", "]")), key=lambda p: (t.find(p[0]) == -1, t.find(p[0])))
        for opener, closer in pairs:  # je bracket age ase seta age try
            s, e = t.find(opener), t.rfind(closer)
            if s != -1 and e > s:
                try:
                    data = json.loads(t[s:e + 1])
                    break
                except json.JSONDecodeError:
                    continue
        if data is None:
            raise AIError("AI er reply JSON chilo na: " + t[:200])
    if isinstance(data, list):
        data = {"title": "", "items": data}
    items, seen = [], set()
    for it in data.get("items") or []:
        if isinstance(it, str):
            it = {"name": it}
        name = str(it.get("name") or "").strip()
        if not name or name.lower() in seen:
            continue
        seen.add(name.lower())
        typ = str(it.get("type") or "other").lower().strip()
        items.append({
            "name": name,
            "type": typ if typ in TYPES else "other",
            "year": str(it.get("year") or "").strip(),
            "note": str(it.get("note") or "").strip(),
            "timestamp": str(it.get("timestamp") or "").strip(),
        })
    return {"title": str(data.get("title") or "").strip(), "items": items}


def _context_block(context):
    return f"\n\nPost caption / extra text:\n{context.strip()}" if context and context.strip() else ""


# ---------------------------------------------------------------- Gemini (free tier)
class Gemini:
    name = "gemini"
    BASE = "https://generativelanguage.googleapis.com"

    def __init__(self):
        self.key = config.get("GEMINI_API_KEY")
        self.model = config.get("GEMINI_MODEL")
        if not self.key:
            raise AIError("Gemini API key nai. Settings e key dao (free: aistudio.google.com/apikey)")
        self.h = {"x-goog-api-key": self.key}

    def _generate(self, parts):
        body = {
            "systemInstruction": {"parts": [{"text": PROMPT}]},
            "contents": [{"role": "user", "parts": parts}],
            "generationConfig": {"responseMimeType": "application/json", "temperature": 0.2},
        }
        url = f"{self.BASE}/v1beta/models/{self.model}:generateContent"
        for attempt in range(3):
            r = requests.post(url, headers=self.h, json=body, timeout=300)
            if r.status_code == 429 or r.status_code >= 500:
                if attempt < 2:
                    time.sleep(5 * (attempt + 1))
                    continue
            break
        if r.status_code == 429:
            raise AIError("Gemini free limit shesh (429). Ektu pore try koro.")
        if not r.ok:
            raise AIError(f"Gemini error {r.status_code}: {r.text[:300]}")
        data = r.json()
        try:
            text = "".join(p.get("text", "") for p in data["candidates"][0]["content"]["parts"])
        except (KeyError, IndexError):
            raise AIError("Gemini kono answer dey nai: " + json.dumps(data)[:300])
        return parse_json(text)

    def _upload(self, path):
        path = Path(path)
        mime = mimetypes.guess_type(path.name)[0] or "video/mp4"
        size = path.stat().st_size
        start = requests.post(
            f"{self.BASE}/upload/v1beta/files", timeout=60,
            headers={**self.h, "X-Goog-Upload-Protocol": "resumable", "X-Goog-Upload-Command": "start",
                     "X-Goog-Upload-Header-Content-Length": str(size),
                     "X-Goog-Upload-Header-Content-Type": mime, "Content-Type": "application/json"},
            json={"file": {"display_name": path.name}})
        if not start.ok:
            raise AIError(f"Gemini upload start fail {start.status_code}: {start.text[:200]}")
        up_url = start.headers.get("x-goog-upload-url")
        with open(path, "rb") as f:
            up = requests.post(up_url, data=f, timeout=600, headers={
                "Content-Length": str(size), "X-Goog-Upload-Offset": "0",
                "X-Goog-Upload-Command": "upload, finalize"})
        if not up.ok:
            raise AIError(f"Gemini upload fail {up.status_code}: {up.text[:200]}")
        f = up.json()["file"]
        for _ in range(90):  # video processing er jonno wait
            if f.get("state") == "ACTIVE":
                return f
            if f.get("state") == "FAILED":
                raise AIError("Gemini video process korte parlo na")
            time.sleep(2)
            f = requests.get(f"{self.BASE}/v1beta/{f['name']}", headers=self.h, timeout=30).json()
        raise AIError("Gemini video processing timeout")

    def _delete(self, f):
        try:
            requests.delete(f"{self.BASE}/v1beta/{f['name']}", headers=self.h, timeout=30)
        except Exception:
            pass

    def from_text(self, text):
        return self._generate([{"text": "Extract the list from this text:\n\n" + text}])

    def from_images(self, paths, context=""):
        parts = []
        for p in paths:
            mime = mimetypes.guess_type(str(p))[0] or "image/jpeg"
            parts.append({"inline_data": {"mime_type": mime, "data": base64.b64encode(Path(p).read_bytes()).decode()}})
        parts.append({"text": "Extract the list from these image(s) (posters / screenshots)." + _context_block(context)})
        return self._generate(parts)

    def from_video(self, path, context=""):
        f = self._upload(path)
        try:
            return self._generate([
                {"file_data": {"mime_type": f["mimeType"], "file_uri": f["uri"]}},
                {"text": "Watch and listen to this whole video and extract the list." + _context_block(context)}])
        finally:
            self._delete(f)


# ---------------------------------------------------------------- Ollama (100% local)
class Ollama:
    name = "ollama"

    def __init__(self):
        self.host = config.get("OLLAMA_HOST").rstrip("/")
        self.model = config.get("OLLAMA_MODEL")
        self.vmodel = config.get("OLLAMA_VISION_MODEL")

    def _chat(self, text, images=None, model=None):
        msg = {"role": "user", "content": text}
        if images:
            msg["images"] = [base64.b64encode(Path(p).read_bytes()).decode() for p in images]
        try:
            r = requests.post(f"{self.host}/api/chat", timeout=900, json={
                "model": model or self.model, "stream": False, "format": "json",
                "options": {"temperature": 0.2},
                "messages": [{"role": "system", "content": PROMPT}, msg]})
        except requests.ConnectionError:
            raise AIError(f"Ollama chalu nai ({self.host}). 'ollama serve' chalao.")
        if not r.ok:
            raise AIError(f"Ollama error {r.status_code}: {r.text[:200]}")
        return parse_json(r.json()["message"]["content"])

    def from_text(self, text):
        return self._chat("Extract the list from this text:\n\n" + text)

    def from_images(self, paths, context=""):
        if self.vmodel:
            return self._chat("Extract the list from these image(s)." + _context_block(context), paths, self.vmodel)
        ocr = "\n".join(lp.ocr_images(paths))
        return self._chat("OCR text from poster/screenshot (may have OCR errors):\n" + ocr + _context_block(context))

    def from_video(self, path, context=""):
        v = lp.video_to_text(path)
        text = "On-screen text from video frames (OCR, may have errors):\n" + "\n".join(v["ocr_lines"])
        if v["transcript"]:
            text += "\n\nSpoken transcript:\n" + v["transcript"]
        return self._chat(text + _context_block(context))


# ---------------------------------------------------------------- Basic (no AI, shudhu OCR)
class Basic:
    """AI chara: OCR/text er line gula e list hisebe dey. Key/Ollama na thakle backup."""
    name = "basic"

    @staticmethod
    def _wrap(lines, title):
        return {"title": title, "items": [{"name": l, "type": "other", "year": "", "note": "", "timestamp": ""}
                                          for l in lp.dedupe(lines)]}

    def from_text(self, text):
        return self._wrap(lp._clean_lines(text), "Text lines")

    def from_images(self, paths, context=""):
        return self._wrap(lp.ocr_images(paths), "OCR (no AI)")

    def from_video(self, path, context=""):
        return self._wrap(lp.video_to_text(path)["ocr_lines"], "Video OCR (no AI)")


def get_provider():
    p = config.get("PROVIDER").lower()
    return {"gemini": Gemini, "ollama": Ollama, "basic": Basic}.get(p, Gemini)()
