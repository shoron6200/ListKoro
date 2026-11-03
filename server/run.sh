#!/usr/bin/env bash
# ListKoro start. Termux e screen off holeo jate na thame: termux-wake-lock
cd "$(dirname "$0")"
command -v termux-wake-lock >/dev/null && termux-wake-lock
pip install -q -U yt-dlp >/dev/null 2>&1 &   # yt-dlp proti bar update (FB change hole bhange)
exec python app.py
