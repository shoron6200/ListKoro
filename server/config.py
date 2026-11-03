"""Settings: .env file theke load hoy, UI theke o change kora jay."""
import os
from pathlib import Path

BASE = Path(__file__).resolve().parent
DATA = BASE / "data"
TMP = DATA / "tmp"
COOKIES = DATA / "cookies.txt"
DB_PATH = DATA / "listkoro.db"
ENV_FILE = BASE / ".env"

for d in (DATA, TMP):
    d.mkdir(parents=True, exist_ok=True)

DEFAULTS = {
    "PROVIDER": "gemini",              # gemini | ollama | basic
    "GEMINI_API_KEY": "",
    "GEMINI_MODEL": "gemini-flash-latest",
    "OLLAMA_HOST": "http://localhost:11434",
    "OLLAMA_MODEL": "qwen2.5:7b",
    "OLLAMA_VISION_MODEL": "",         # e.g. qwen2.5vl:7b (optional)
    "TESSERACT_LANG": "eng",           # e.g. eng+ben
    "WHISPER_MODEL": "small",          # faster-whisper installed thakle use hobe
    "MAX_VIDEO_MIN": "20",
    "HOST": "127.0.0.1",
    "PORT": "8000",
}
EDITABLE = {"PROVIDER", "GEMINI_API_KEY", "GEMINI_MODEL", "OLLAMA_HOST",
            "OLLAMA_MODEL", "OLLAMA_VISION_MODEL", "TESSERACT_LANG"}


def _read_env_file():
    out = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def get(key):
    file_vals = _read_env_file()
    return os.environ.get(key) or file_vals.get(key) or DEFAULTS.get(key, "")


def save(updates: dict):
    vals = _read_env_file()
    for k, v in updates.items():
        if k in EDITABLE and v is not None:
            vals[k] = str(v).strip()
            os.environ.pop(k, None)  # file value ke priority dao
    ENV_FILE.write_text("".join(f"{k}={v}\n" for k, v in vals.items()), encoding="utf-8")
