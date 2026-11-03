# ListKoro 📋

FB / Instagram / YouTube **link**, **poster/screenshot** ba **text** dao, AI automatic **movie / AI tool er list** ber kore dibe.

- Video hole 360p te download kore AI ke dey. Gemini video dekhe ar shune, tai screen er lekha ar mukher kotha dutoi dhore.
- Photo/poster post hole poster image theke pore
- Text paste korle text theke list ber kore
- Sob result **History** te save thake, search kora jay
- Phone e **install** kora jay (PWA). FB app e **Share → ListKoro** dile direct kaj shuru hoy.

## AI option (sob free)
| Provider | Kemon | Kothay |
|---|---|---|
| `gemini` (default) | Best. Video, audio, poster sob bujhe | Free API key: https://aistudio.google.com/apikey |
| `ollama` | 100% local, internet lage na | PC te (8GB+ RAM). OCR + local LLM |
| `basic` | AI chara, shudhu OCR er line | Jekono jaygay, backup hisebe |

> Note: Gemini free tier e Google tomar data product improve korar jonno use korte pare. Movie/tool list er jonno eta shadharonoto problem na, kintu sensitive kichu pathaio na.

---

## 📱 Android phone e chalano (Termux), PC lagbe na

1. **Termux** install koro **F-Droid** theke (Play Store er ta purano): https://f-droid.org/packages/com.termux/
2. Termux khule eigula chalao:
```bash
pkg update -y && pkg install -y python ffmpeg tesseract git
```
```bash
termux-setup-storage
```
3. Code nao:
```bash
cd ~ && git clone https://github.com/shoron6200/ListKoro && cd ListKoro/server
```
```bash
pip install -r requirements.txt
```
```bash
chmod +x run.sh && ./run.sh
```
4. Chrome e kholo: **http://localhost:8000**
5. ⚙️ Settings e Gemini API key paste koro, tarpor Save
6. Chrome menu (⋮) → **Add to Home screen / Install app**. Ekhon FB te kono post e **Share** chaple **ListKoro** dekhabe 🎉

Porer bar theke shudhu: Termux kholo, tarpor `cd listkoro && ./run.sh`

## 💻 PC te (ar fully local Ollama chaile)
```bash
pip install -r requirements.txt
```
```bash
python app.py
```
(ffmpeg ar tesseract o install korte hobe: apt / brew / choco diye)

- Fully local AI: https://ollama.com install koro, `ollama pull qwen2.5:7b` chalao, tarpor Settings e provider **Ollama** select koro
- Poster er jonno vision model (optional): `ollama pull qwen2.5vl:7b`, tarpor Settings er "Vision model" e boshao
- Mukher kotha (speech) local e bujhte chaile: `pip install faster-whisper`
- Phone theke PC er app use korte: `.env` e `HOST=0.0.0.0` dao, tarpor **Tailscale** (free) duita device e install kore `tailscale serve 8000` chalao. HTTPS link pabe, share target o kaj korbe. ⚠️ Public internet e kokhono open korba na.

## 🍪 Private post (cookies)
1. PC Chrome e **"Get cookies.txt LOCALLY"** extension install koro
2. facebook.com e login thaka obosthay extension theke Export koro (Netscape format)
3. App er ⚙️ Settings e giye **Upload cookies.txt** koro

Instagram er jonno instagram.com e giye same vabe export koro, tarpor duita file er content ekta file e jure upload koro. Cookies kichu din por expire hoy, tokhon abar export korte hobe. Beshi request korle account e checkpoint ashte pare, tai normal use er moddhe rakho.

## Somossa hole
- **"Link theke kichu ana gelo na"**: post private hole cookies dao. Na hole post er **screenshot** Poster tab e dao.
- **FB change hoye download bondho hole**: `pip install -U yt-dlp` (run.sh nijei eta kore)
- **Gemini 429**: free limit shesh, kichukkhon por try koro
- **Bangla lekha OCR (basic/ollama mode)**: tesseract er Bangla language data lagbe, tarpor Settings e OCR language `eng+ben` dao

## Files
`app.py` server · `fetcher.py` yt-dlp download · `ai.py` Gemini/Ollama/Basic · `local_pipeline.py` ffmpeg+OCR · `db.py` history · `static/` UI
