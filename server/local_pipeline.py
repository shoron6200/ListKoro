"""Fully local kaj: ffmpeg diye frame/audio, tesseract diye OCR, (optional) faster-whisper diye transcript."""
import difflib, re, shutil, subprocess, tempfile
from pathlib import Path
import config


def have(cmd):
    return shutil.which(cmd) is not None


def _run(args, timeout=600):
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout)


def extract_frames(video, outdir, every_sec=1.5, max_frames=80):
    """Proti `every_sec` second e ekta frame, height 720 e scale (choto 360p text OCR er jonno boro kora)."""
    outdir = Path(outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    _run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(video),
          "-vf", f"fps=1/{every_sec},scale=-2:720",
          "-frames:v", str(max_frames), str(outdir / "f_%04d.png")])
    return sorted(outdir.glob("f_*.png"))


def ocr_image(path, psm=11, min_conf=55):
    """Tesseract TSV output theke shudhu confident word rakha (noisy background er garbage bad)."""
    if not have("tesseract"):
        raise RuntimeError("tesseract install nai (Termux: pkg install tesseract)")
    r = _run(["tesseract", str(path), "stdout", "-l", config.get("TESSERACT_LANG"), "--psm", str(psm), "tsv"],
             timeout=120)
    lines = {}
    for row in r.stdout.splitlines()[1:]:
        c = row.split("\t")
        if len(c) < 12 or not c[11].strip():
            continue
        try:
            conf = float(c[10])
        except ValueError:
            continue
        if conf >= min_conf:
            key = (int(c[2]), int(c[3]), int(c[4]))  # block, paragraph, line
            lines.setdefault(key, []).append(c[11].strip())
    return "\n".join(" ".join(w) for _, w in sorted(lines.items()))


def _clean_lines(text):
    out = []
    for line in text.splitlines():
        line = re.sub(r"\s+", " ", line).strip(" |_~-—=•*·.,:;'\"`")
        letters = sum(ch.isalnum() for ch in line)
        has_word = any(sum(ch.isalpha() for ch in w) >= 3 for w in line.split())
        if has_word and letters / max(len(line), 1) > 0.5:
            out.append(line)
    return out


def dedupe(lines, threshold=0.85):
    kept, norms = [], []
    for line in lines:
        n = re.sub(r"[^a-z0-9ঀ-৿]+", "", line.lower())
        if not n:
            continue
        if any(n == m or difflib.SequenceMatcher(None, n, m).ratio() >= threshold for m in norms[-60:]):
            continue
        kept.append(line)
        norms.append(n)
    return kept


def ocr_images(paths):
    lines = []
    for p in paths:
        lines += _clean_lines(ocr_image(p))
    return dedupe(lines)


def transcribe(video):
    """faster-whisper installed thakle audio -> text. Na thakle khali string."""
    try:
        from faster_whisper import WhisperModel  # type: ignore
    except Exception:
        return ""
    with tempfile.TemporaryDirectory(dir=config.TMP) as td:
        wav = Path(td) / "a.wav"
        _run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(video), "-vn", "-ac", "1", "-ar", "16000", str(wav)])
        if not wav.exists():
            return ""
        model = WhisperModel(config.get("WHISPER_MODEL"), device="auto", compute_type="int8")
        segs, _ = model.transcribe(str(wav), vad_filter=True)
        return "\n".join(f"[{int(s.start)//60:02d}:{int(s.start)%60:02d}] {s.text.strip()}" for s in segs)


def video_to_text(video):
    with tempfile.TemporaryDirectory(dir=config.TMP) as td:
        frames = extract_frames(video, td)
        ocr = ocr_images(frames) if frames else []
    return {"ocr_lines": ocr, "transcript": transcribe(video)}
