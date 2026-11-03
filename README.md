# ListKoro 📋

FB / Instagram / YouTube **link**, **poster/screenshot** ba **text** dao → AI automatic **movie / AI tool er list** ber kore dibe.

Duita version, ekoi UI:

| | Ki | Kothay chole |
|---|---|---|
| 📱 **Android APK** (`android/`) | Pura app phone ei: yt-dlp + ffmpeg bhitore, ML Kit OCR, Gemini AI | Install korlei hoye gelo, Termux lage na |
| 🐍 **Python server** (`server/`) | Flask + yt-dlp + tesseract | Termux / PC (Ollama diye 100% local) |

## Features
- Video/reel link → 360p download → AI dekhe + shune list dey (screen er lekha + mukher kotha + caption)
- Photo/poster post → poster image theke list
- Text paste → list
- FB/IG app e **Share → ListKoro** → direct kaj shuru
- Private post: app er bhitore **Facebook / Instagram e login** (cookies auto save)
- History + search, Copy all, Share, protiti item e Google search
- AI: **Gemini** (free key, best) · **Ollama** (PC te local, phone theke WiFi diye) · **Basic OCR** (AI chara)

## 📦 APK banano (GitHub Actions, PC lagbe na)
1. Repo te push hole nijei build hoy. Othoba: **Actions → Android APK → Run workflow**
2. Run shesh hole (≈10-15 min) → run e dhuko → niche **Artifacts → listkoro-apk** download → zip khulle `.apk`
3. Phone e install (unknown source allow korte hobe)

**Size choto rakhte:** default shudhu `arm64-v8a` (2017+ er prai sob phone) ar R8 shrink on.
Run workflow er somoy `abi` / `minify` change kora jay. Purano 32-bit phone hole `arm64-v8a,armeabi-v7a`. Launch e crash korle `minify = false`.

**Signing (update er jonno jaruri):** repo **Settings → Secrets and variables → Actions** e 4 ta secret dao:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
Secret na thakle proti build alada debug key te sign hoy → notun APK install korar age purono ta uninstall korte hobe (history muche jabe).

## 📱 App setup
1. App khulo → ⚙️ Settings → free Gemini key dao (https://aistudio.google.com/apikey) → Save
2. Private post er jonno: Settings → **Facebook e login** → login → **✓ Save**
3. FB te kono reel/post → **Share → ListKoro** 🎉

Prothom bar link dile downloader ready hote kichu second lage (yt-dlp unpack hoy). App dine ekbar yt-dlp nijei update kore.

## 🐍 Python server
Dekho [`server/README.md`](server/README.md) (Termux / PC / Ollama setup).

## Structure
```
android/            Kotlin app (WebView UI + local server + yt-dlp + ML Kit)
  app/src/main/java/com/listkoro/app/
    App.kt            yt-dlp/ffmpeg init, local server start, daily yt-dlp update
    MainActivity.kt   WebView, Share intent, file picker, JS bridge
    LoginActivity.kt  FB/IG login → cookies.txt
    LocalServer.kt    /api/* (Python server er moto same API)
    Jobs.kt           background job runner
    Fetcher.kt        yt-dlp download + og:image fallback
    Ai.kt             Gemini / Ollama / Basic
    Ocr.kt            ML Kit OCR + video frame
    Store.kt          settings + history (SQLite)
server/             Python version (Flask)
  static/           Web UI — APK o eitai use kore (build e assets hisebe jay)
.github/workflows/android-apk.yml
```

> Shudhu personal use er jonno. Gemini free tier e Google data use korte pare, sensitive kichu pathaio na.
