const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const NATIVE = window.ListKoroApp || null;   // Android app er bhitore chalale
if (NATIVE) document.documentElement.classList.add("native");
const TYPE_LABEL = {movie:"Movie",series:"Series",anime:"Anime",tool:"AI tool",app:"App",website:"Website",book:"Book",other:"Other"};
const PROVIDER_LABEL = {gemini:"Gemini",ollama:"Ollama",basic:"Basic OCR"};
const JSONH = {"Content-Type":"application/json; charset=utf-8"};
let current = null, images = [], settingsCache = null;

const icon = (id, cls="") => `<svg class="i ${cls}"><use href="#${id.includes("-") ? id : "i-"+id}"/></svg>`;
function toast(msg){const t=$("#toast");t.textContent=msg;t.classList.add("show");clearTimeout(t._h);t._h=setTimeout(()=>t.classList.remove("show"),2200);}
async function api(path, opts={}){
  const r = await fetch(path, opts);
  const data = await r.json().catch(()=>({error:"The server sent a reply ListKoro couldn't read"}));
  if(!r.ok) throw new Error(data.error || r.statusText);
  return data;
}
const post = (path, body) => api(path, {method:"POST", headers:JSONH, body:JSON.stringify(body||{})});
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const store = {get:(k,d)=>{try{return localStorage.getItem(k) ?? d}catch(e){return d}}, set:(k,v)=>{try{localStorage.setItem(k,v)}catch(e){}}};

function fmtBytes(b){
  if(!b && b!==0) return "";
  const u=["B","KB","MB","GB"]; let i=0; while(b>=1024 && i<u.length-1){ b/=1024; i++; }
  return (b>=100||i===0 ? Math.round(b) : b.toFixed(1)) + " " + u[i];
}
function fmtSecs(s){
  if(s==null || !isFinite(s)) return "";
  s = Math.round(s); return s>=60 ? `${Math.floor(s/60)}m ${s%60}s` : `${s}s`;
}
function fmtDur(s){
  if(!s) return ""; s=Math.round(s);
  const h=Math.floor(s/3600), m=Math.floor(s%3600/60), x=String(s%60).padStart(2,"0");
  return h ? `${h}:${String(m).padStart(2,"0")}:${x}` : `${m}:${x}`;
}
function ago(ts){
  const d = Date.now()/1000 - ts;
  if(d < 60) return "Just now";
  if(d < 3600) return `${Math.floor(d/60)}m ago`;
  if(d < 86400) return `${Math.floor(d/3600)}h ago`;
  if(d < 7*86400) return `${Math.floor(d/86400)}d ago`;
  return new Date(ts*1000).toLocaleDateString(undefined, {day:"numeric", month:"short"});
}
function dayGroup(ts){
  const d = new Date(ts*1000), now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()/1000;
  if(ts >= start) return "Today";
  if(ts >= start - 86400) return "Yesterday";
  if(d.getFullYear() === now.getFullYear()) return d.toLocaleDateString(undefined, {month:"long"});
  return String(d.getFullYear());
}
function progressText(p){
  if(!p) return "";
  const parts = [];
  if(p.total) parts.push(`${fmtBytes(p.done)} of ${fmtBytes(p.total)}`); else if(p.done) parts.push(fmtBytes(p.done));
  if(p.speed) parts.push(`${fmtBytes(p.speed)}/s`);
  if(p.eta!=null && p.eta>0) parts.push(`${fmtSecs(p.eta)} left`);
  return parts.join(" · ");
}
function setBar(track, p){
  const on = p && p.pct!=null;
  track.classList.toggle("indet", !on);
  track.querySelector("i").style.setProperty("--p", on ? Math.min(100, p.pct)/100 : 0);
}

// Backend er step gula Banglish e ashe; UI te English dekhai (ojana step hole jemon ache temon)
const STEP_EN = [
  [/^Shuru hocche/i, "Starting"], [/^Link check hocche/i, "Checking the link"], [/^Post info ana hocche/i, "Getting post details"],
  [/^Video info ana hocche/i, "Getting video info"], [/^Info ana hocche/i, "Getting video info"], [/^Download hocche/i, "Downloading"],
  [/^Phone e save hocche/i, "Saving to your phone"], [/^Save hocche/i, "Saving"], [/^AI video dekhche/i, "Watching the video"],
  [/^AI audio shunche/i, "Listening to the audio"], [/^AI caption porche/i, "Reading the caption"], [/^AI poster\/image porche/i, "Reading the images"],
  [/^AI poster porche/i, "Reading the poster"], [/^AI text porche/i, "Reading the text"], [/^Downloader abar start korchi/i, "Restarting the downloader"],
  [/^Video pawa jay nai, poster\/image khujchi/i, "No video found, looking for a poster"], [/^yt-dlp update hocche/i, "Updating yt-dlp, one moment"],
  [/^Gemini busy, abar try korchi \((.*)\)/i, "Gemini is busy, retrying ($1)"], [/^Gemini busy, onno model try korchi \((.*)\)/i, "Gemini is busy, trying $1"],
  [/^Error$/i, "Stopped"], [/^Done ✓?$/i, "Done"], [/^Cancel hocche/i, "Cancelling"], [/^Cancel hoyeche/i, "Cancelled"], [/^Cancel kora hoyeche/i, "Cancelled"],
];
const stepEn = s => { s = String(s||"").replace(/…$/,""); for(const [re,to] of STEP_EN) if(re.test(s)) return s.replace(re, to); return s; };

/* ---------- sources ---------- */
function platformOf(url){
  const u = String(url||"").toLowerCase();
  if(/facebook\.com|fb\.watch|fb\.com/.test(u)) return {name:"Facebook", glyph:"c-facebook"};
  if(/instagram\.com/.test(u)) return {name:"Instagram", glyph:"c-instagram"};
  if(/youtube\.com|youtu\.be/.test(u)) return {name:"YouTube", glyph:"c-youtube"};
  if(/tiktok\.com/.test(u)) return {name:"TikTok", glyph:"c-tiktok"};
  return {name:"the web", glyph:"globe"};
}
function sourceOf(r){
  if(r.source_type==="image") return {label:"From image", glyph:"image"};
  if(r.source_type==="text") return {label:"From text", glyph:"text"};
  const p = platformOf(r.source); return {label:"From "+p.name, glyph:p.glyph};
}
const srcTile = (glyph, cls="") => `<span class="src-tile ${glyph.startsWith("c-")?"has-logo":""} ${cls}">${icon(glyph, glyph.startsWith("c-")?"logo":"")}</span>`;
// Item er thumbnail slot: type er rong e initials (poster data nai)
const initials = n => String(n||"?").replace(/[^\p{L}\p{N} ]/gu," ").trim().split(/\s+/).slice(0,2).map(w=>w[0]).join("").toUpperCase() || "?";
/* button e loading state: kaj cholar somoy spinner */
function busy(btn, on){ if(!btn) return; btn.classList.toggle("loading", on); btn.disabled = on; btn.setAttribute("aria-busy", on); }
const plural = (n, w) => `${n} ${w}${n===1?"":"s"}`;

/* ---------- router: tabs + pushed screens ---------- */
const TABS = ["home","history","downloads","settings"];
let stack = ["home"];
const cur = () => stack[stack.length-1];
function show(name, dir){
  $$(".screen").forEach(s=>s.classList.toggle("hidden", s.dataset.screen!==name));
  const el = $(`.screen[data-screen="${name}"]`);
  el.classList.remove("enter","enter-back");
  el.classList.remove("fade-in");
  if(dir){ void el.offsetWidth; el.classList.add(dir<0 ? "enter-back" : "enter"); }
  else if(show._ready){ void el.offsetWidth; el.classList.add("fade-in"); }
  show._ready = true;
  document.body.dataset.view = name;
  document.body.classList.toggle("pushed", !TABS.includes(name));
  $$("#tabbar button").forEach(b=>b.classList.toggle("on", b.dataset.tab===stack[0]));
  window.scrollTo(0,0); document.body.classList.remove("scrolled");
  onShow(name);
}
function tab(name){ const same = stack.length===1 && cur()===name; stack=[name]; show(name, same?0:0); store.set("tab", name); }
function push(name){ if(cur()===name) return; stack.push(name); show(name, 1); }
function replaceTop(name){ stack[stack.length-1]=name; show(name, 1); }
function back(){
  if(stack.length>1){ stack.pop(); show(cur(), -1); return true; }
  if(cur()!=="home"){ tab("home"); return true; }
  return false;
}
function onShow(name){
  if(name==="home") loadHome();
  if(name==="history") loadHistory();
  if(name==="downloads") refreshDownloads();
  if(name==="settings") loadSettings().catch(e=>toast(e.message));
  if(name==="about") loadAbout();
}
document.addEventListener("click", e => {
  const t = e.target.closest("[data-tab],[data-push],.back"); if(!t) return;
  if(t.classList.contains("back")) back();
  else if(t.dataset.tab) tab(t.dataset.tab);
  else push(t.dataset.push);
});

/* ---------- menu sheet ---------- */
const menuDlg = $("#menuDlg");
function openMenu(title, items){
  $("#menuBody").innerHTML = (title ? `<p class="menu-title">${esc(title)}</p>` : "") +
    items.map((it,i)=>`<button type="button" data-mi="${i}" class="${it.danger?"danger":""}">${icon(it.icon, it.icon.startsWith("c-")?"logo":"")}<span>${esc(it.label)}</span></button>`).join("");
  $("#menuBody").onclick = e => { const b = e.target.closest("[data-mi]"); if(!b) return; menuDlg.close(); items[+b.dataset.mi].fn(); };
  menuDlg.showModal();
}
menuDlg.addEventListener("click", e => { if(e.target===menuDlg) menuDlg.close(); });
function openExternal(url){ const a=document.createElement("a"); a.href=url; a.target="_blank"; a.rel="noopener"; document.body.append(a); a.click(); a.remove(); }

/* ---------- home ---------- */
async function loadHome(){
  const rows = await api("/api/history").catch(()=>null);
  if(rows) window._recent = rows.slice(0,3);
  const r = window._recent || [];
  $("#homeRecent").innerHTML = r.length ? r.map((x,i)=>{
    const s = sourceOf(x);
    return `<button class="row tap" type="button" data-recent="${i}">${srcTile(s.glyph)}
      <span class="row-main"><b>${esc(x.title||"List")}</b><small>${esc(s.label)} · ${ago(x.created)}</small></span>
      <span class="icon-btn" aria-hidden="true">${icon("chev")}</span></button>`;
  }).join("") : `<div class="empty">${icon("list")}<b>No lists yet</b><small>Your lists show up here once you make one.</small></div>`;
  renderHomeRunning();
}
$("#homeRecent").onclick = e => { const b = e.target.closest("[data-recent]"); if(b) render(window._recent[+b.dataset.recent]); };
$("#homeStatus").onclick = () => tab("settings");
$("#keyNoticeGo").onclick = () => tab("settings");
function renderHomeStatus(s){
  const btn = $("#homeStatus");
  const ai = s.provider!=="gemini" || s.gemini_key_set, dl = (s.ytdl_ok ?? s.ffmpeg) !== false;
  btn.classList.toggle("ok", ai && dl); btn.classList.toggle("bad", !(ai && dl));
  $("#homeStatusText").textContent = ai && dl ? "Ready" : "Needs setup";
  $("#keyNotice").classList.toggle("hidden", ai);
}

/* ---------- list job (step log + progress + cancel) ---------- */
let listJob = null, lastJob = null, readyResult = null;
const goBtns = ["#goLink","#goImage","#goText"];

async function startList(body, btn){
  busy(btn, true);
  try{
    const opts = body instanceof FormData ? {method:"POST", body} : {method:"POST", headers:JSONH, body:JSON.stringify(body)};
    const {job} = await api("/api/jobs", opts);
    busy(btn, false);
    watchList(job);
  }catch(e){ busy(btn, false); toast(e.message); }
}

function resetJobScreen(){
  $("#jobTitle").textContent = "Analyzing with AI";
  $("#jobSub").textContent = "Reading your content and pulling out the list";
  $("#jobStep").textContent = "Starting"; $("#jobTime").textContent = "0s";
  $("#jobLog").innerHTML = ""; $("#jobErr").classList.add("hidden");
  $("#jobProg").classList.add("hidden");
  $("#jobMark").className = "job-mark run";
  $("#jobCancel").classList.remove("hidden"); $("#jobCancel").disabled = false;
  $("#jobRetry").classList.add("hidden");
}

function renderJobCard(j){
  const running = j.status==="running";
  $("#jobTitle").textContent = running ? "Analyzing with AI" : j.status==="done" ? "Your list is ready" : j.status==="cancelled" ? "Cancelled" : "Couldn't make the list";
  $("#jobSub").textContent = running ? (j.progress ? "Fetching the post so the AI can read it" : "Reading your content and pulling out the list")
    : j.status==="done" ? "Opening your list" : j.status==="cancelled" ? "Nothing was saved" : "Nothing was saved. Check the message below and try again";
  $("#jobStep").textContent = stepEn(j.step);
  $("#jobTime").textContent = fmtSecs((j.updated && !running ? j.updated : Date.now()/1000) - j.created) || "0s";
  const p = j.progress;
  $("#jobProg").classList.toggle("hidden", !p || !running);
  if(p){
    setBar($("#jobProg .bar-track"), p);
    $("#jobBarText").textContent = [p.pct!=null ? `${p.pct}%` : "", progressText(p)].filter(Boolean).join(" · ");
  }
  const mark = $("#jobMark");
  mark.className = "job-mark " + (running ? "run" : j.status==="done" ? "done" : "fail");
  // shesh "Done/Error/Cancel" line ta bad, baki gula step
  let log = (j.log || []).slice();
  if(!running && log.length) log.pop();
  $("#jobLog").innerHTML = log.map((l,i)=>{
    const last = i===log.length-1;
    const cls = last && running ? "now" : (last && j.status==="error" ? "bad" : "done");
    const mk = cls==="done" ? icon("check") : cls==="bad" ? icon("x") : "";
    return `<li class="${cls}"><span class="st">${mk}</span><span>${esc(stepEn(l.msg))}</span><small>${fmtSecs(l.t)}</small></li>`;
  }).join("");
  $("#jobCancel").classList.toggle("hidden", !running);
  $("#jobRetry").classList.toggle("hidden", running || j.status==="done");
}

function renderHomeRunning(j){
  const b = $("#homeRunning");
  if(listJob){
    b.classList.remove("hidden");
    b.querySelector("b").textContent = "Making your list";
    b.querySelector(".ring").classList.remove("hidden");
    if(j) $("#homeRunningStep").textContent = stepEn(j.step);
  } else if(readyResult){
    b.classList.remove("hidden");
    b.querySelector("b").textContent = "Your list is ready";
    b.querySelector(".ring").classList.add("hidden");
    $("#homeRunningStep").textContent = readyResult.title || "Open it";
  } else b.classList.add("hidden");
}
$("#homeRunning").onclick = () => { if(readyResult){ const r = readyResult; readyResult = null; render(r); } else if(listJob) push("job"); };

async function watchList(job){
  listJob = job; lastJob = job; readyResult = null;
  resetJobScreen();
  if(cur()==="job") show("job", 0); else push("job");
  goBtns.forEach(id=>$(id).disabled=true);
  history.replaceState(null, "", "/?job="+job);
  while(listJob===job){
    let j;
    try{ j = await api("/api/jobs/"+job); }
    catch(e){ showJobError(e.message); break; }
    renderJobCard(j); renderHomeRunning(j);
    if(j.status==="done"){
      listJob = null;
      if(cur()==="job") render(j.result, true);
      else { readyResult = j.result; toast("Your list is ready"); }
      break;
    }
    if(j.status==="error"){ showJobError(j.error); break; }
    if(j.status==="cancelled"){ toast("Cancelled"); if(cur()==="job") back(); break; }
    await sleep(1000);
  }
  if(listJob===job) listJob = null;
  history.replaceState(null,"","/");
  goBtns.forEach(id=>$(id).disabled=false);
  renderHomeRunning();
}
function showJobError(msg){
  $("#jobErr").classList.remove("hidden");
  $("#jobErrText").textContent = msg;
  $("#jobTitle").textContent = "Couldn't make the list";
  $("#jobSub").textContent = "Nothing was saved. Check the message below and try again";
  $("#jobMark").className = "job-mark fail";
  $("#jobCancel").classList.add("hidden");
  $("#jobRetry").classList.remove("hidden");
}
$("#jobCancel").onclick = async () => {
  if(!listJob) return;
  $("#jobCancel").disabled = true;
  try{ await post(`/api/jobs/${listJob}/cancel`); }catch(e){ toast(e.message); }
  $("#jobCancel").disabled = false;
};
$("#jobRetry").onclick = () => back();

/* ---------- result ---------- */
function searchUrl(it){
  const extra = {movie:"movie",series:"series",anime:"anime",tool:"AI tool",app:"app",website:"",book:"book"}[it.type] ?? "";
  return "https://www.google.com/search?q=" + encodeURIComponent([it.name, it.year, extra].filter(Boolean).join(" "));
}
function render(r, fromJob){
  current = r;
  const s = sourceOf(r);
  $("#resTile").outerHTML = srcTile(s.glyph, "big").replace('class="src-tile', 'id="resTile" class="src-tile');
  $("#resTitle").textContent = r.title || "List";
  $("#resMeta").textContent = `${s.label} · ${ago(r.created)}${r.provider ? " · " + (PROVIDER_LABEL[String(r.provider).split(":")[0]] || r.provider) : ""}`;
  $("#resWarnText").textContent = r.warning || "";
  $("#resWarn").classList.toggle("hidden", !r.warning);
  $("#resDownload").classList.toggle("hidden", !(r.source_type==="url" && /^https?:/.test(r.source||"")));
  $("#resCount").textContent = r.items.length;
  $("#resList").innerHTML = r.items.map((it,i)=>`
    <li class="item">
      <span class="idx">${i+1}</span>
      <span class="mono" style="--c:var(--t-${esc(it.type)})" aria-hidden="true">${esc(initials(it.name))}</span>
      <div class="item-main">
        <b>${esc(it.name)}</b>
        <span class="item-meta"><i class="tdot" style="--c:var(--t-${esc(it.type)})"></i>${esc(TYPE_LABEL[it.type]||it.type)}${it.year?` · ${esc(it.year)}`:""}${it.timestamp?` · at ${esc(it.timestamp)}`:""}</span>
        ${it.note?`<span class="item-note">${esc(it.note)}</span>`:""}
      </div>
      <button class="icon-btn" type="button" data-item="${i}" aria-label="More for ${esc(it.name)}">${icon("kebab")}</button>
    </li>`).join("");
  if(fromJob && cur()==="job") replaceTop("result"); else push("result");
}
$("#resList").onclick = e => {
  const b = e.target.closest("[data-item]"); if(!b) return;
  const it = current.items[+b.dataset.item];
  openMenu(it.name, [
    {icon:"search", label:"Search on Google", fn:()=>openExternal(searchUrl(it))},
    {icon:"copy", label:"Copy name", fn:()=>copy(it.name + (it.year?` (${it.year})`:""))},
  ]);
};
function listText(r){
  return (r.title?r.title+"\n":"") + r.items.map((it,i)=>`${i+1}. ${it.name}${it.year?` (${it.year})`:""}${it.note?` - ${it.note}`:""}`).join("\n");
}
async function copy(text){
  if(NATIVE){ NATIVE.copy(text); toast("Copied"); return; }
  try{ await navigator.clipboard.writeText(text); }
  catch(e){ const ta=document.createElement("textarea"); ta.value=text; document.body.append(ta); ta.select(); document.execCommand("copy"); ta.remove(); }
  toast("Copied");
}
async function shareList(){
  if(!current) return;
  if(NATIVE) return NATIVE.share(listText(current));
  if(navigator.share){ try{ await navigator.share({title:current.title, text:listText(current)}); }catch(e){} }
  else copy(listText(current));
}
$("#copyAll").onclick = () => current && copy(listText(current));
$("#shareAll").onclick = shareList;
$("#shareTop").onclick = shareList;
$("#resDownload").onclick = () => current && openDownload(current.source);

/* ---------- paste helper ---------- */
async function pasteInto(input){
  if(NATIVE){ const v = NATIVE.paste().trim(); if(v) input.value = v; else toast("Clipboard is empty"); return; }
  try{ input.value = (await navigator.clipboard.readText()).trim(); }
  catch(e){ toast("Allow clipboard access, or long-press the field to paste"); }
}

/* ---------- list inputs ---------- */
$("#pasteBtn").onclick = () => pasteInto($("#urlInput"));
$("#goLink").onclick = () => {
  const v = $("#urlInput").value.trim();
  if(!v) return toast("Paste a link first");
  startList({url:v}, $("#goLink"));
};
$("#urlInput").onkeydown = e => { if(e.key==="Enter") $("#goLink").click(); };
$("#imgInput").onchange = e => {
  images = [...e.target.files].slice(0,10);
  $("#thumbs").innerHTML = images.map(f=>`<img src="${URL.createObjectURL(f)}" alt="">`).join("");
  $("#thumbs").classList.toggle("hidden", !images.length);
  $("#drop b").textContent = images.length ? plural(images.length, "image") + " selected" : "Tap to upload";
  $("#drop small").textContent = images.length ? "Tap to choose again" : "Screenshot, poster or image. Up to 10 at once.";
};
$("#goImage").onclick = () => {
  if(!images.length) return toast("Choose an image first");
  const fd = new FormData();
  images.forEach(f=>fd.append("image", f));
  fd.append("text", $("#imgCaption").value);
  startList(fd, $("#goImage"));
};
const syncCount = () => { $("#textCount").textContent = plural($("#textInput").value.length, "character").replace(/^\d+/, n=>Number(n).toLocaleString()); };
$("#textInput").oninput = syncCount;
$("#goText").onclick = () => {
  const v = $("#textInput").value.trim();
  if(!v) return toast("Paste some text first");
  startList({text:v}, $("#goText"));
};
const EXAMPLES = {
  movie:"Films everyone should see at least once:\n1. Spirited Away (2001)\n2. Parasite (2019)\n3. The Grand Budapest Hotel\n4. Mad Max: Fury Road\n5. Pather Panchali (1955)",
  series:"Weekend binge list: Dark, Severance, The Bear, Shogun and Chernobyl. If you liked Breaking Bad, try Better Call Saul next.",
  tools:"AI tools I use for video editing: CapCut for quick cuts, Descript to edit by transcript, Runway for effects and ElevenLabs for voiceovers.",
  anime:"Starter anime list 👇 Attack on Titan, Frieren: Beyond Journey's End, Mob Psycho 100, Vinland Saga, Your Name (movie)",
};
$("#examples").onclick = e => { const b = e.target.closest("[data-ex]"); if(!b) return; $("#textInput").value = EXAMPLES[b.dataset.ex]; syncCount(); $("#textInput").focus(); };

/* ---------- download: formats ---------- */
let fmtJob = null, fmtData = null, fmtPick = 0;
const thumbsByTitle = new Map();
function openDownload(url){
  push("video");
  $("#dlUrl").value = url;
  $("#dlGo").click();
}
$("#dlPaste").onclick = () => pasteInto($("#dlUrl"));
$("#dlUrl").onkeydown = e => { if(e.key==="Enter") $("#dlGo").click(); };
$("#dlGo").onclick = async () => {
  const url = $("#dlUrl").value.trim();
  if(!url) return toast("Paste a video link first");
  $("#dlErr").classList.add("hidden");
  busy($("#dlGo"), true);
  try{
    const {job} = await post("/api/jobs", {mode:"formats", url});
    fmtJob = job;
    $("#dlStatus").classList.remove("hidden");
    while(fmtJob===job){
      const j = await api("/api/jobs/"+job);
      $("#dlStep").textContent = stepEn(j.step);
      $("#dlTime").textContent = fmtSecs(Date.now()/1000 - j.created) || "0s";
      if(j.status==="done"){ showFormats(j.result); break; }
      if(j.status==="error"){ showDlError(j.error); break; }
      if(j.status==="cancelled") break;
      await sleep(800);
    }
  }catch(e){ showDlError(e.message); }
  fmtJob = null;
  $("#dlStatus").classList.add("hidden");
  busy($("#dlGo"), false);
};
$("#dlStatusCancel").onclick = async () => {
  const job = fmtJob; fmtJob = null;
  $("#dlStatus").classList.add("hidden"); busy($("#dlGo"), false);
  if(job) post(`/api/jobs/${job}/cancel`).catch(()=>{});
};
function showDlError(msg){
  $("#dlErrText").textContent = msg;
  $("#dlErr").classList.remove("hidden");
}
const qBadge = label => { const p = parseInt(label); return label==="MP3" ? "Audio" : p>=2160 ? "4K" : p>=1440 ? "2K" : p>=1080 ? "FHD" : p>=720 ? "HD" : ""; };
function showFormats(r){
  fmtData = r; fmtPick = 0;
  const img = $("#dlThumb");
  if(r.thumbnail) img.src = r.thumbnail; else img.removeAttribute("src");
  img.onerror = () => img.removeAttribute("src");
  $("#dlDur").textContent = fmtDur(r.duration); $("#dlDur").classList.toggle("hidden", !r.duration);
  $("#dlTitle").textContent = r.title || "Video";
  const p = platformOf(r.url);
  $("#dlMeta").textContent = [r.uploader || (p.name!=="the web" ? p.name : ""), r.duration ? fmtDur(r.duration) : ""].filter(Boolean).join(" · ");
  renderOptions();
  push("quality");
}
$("#dlPlay").onclick = () => fmtData && openExternal(fmtData.url);
function renderOptions(){
  $("#dlOptions").innerHTML = fmtData.options.map((o,i)=>{
    const b = qBadge(o.label);
    return `<button class="q ${i===fmtPick?"on":""}" data-opt="${i}" type="button" role="radio" aria-checked="${i===fmtPick}">
      <span class="radio"></span>${esc(o.label)}${b?` <em>${b}</em>`:""}<small>${o.size ? "~ " + fmtBytes(o.size) : (o.kind==="audio" ? "Audio" : "")}</small></button>`;
  }).join("");
}
$("#dlOptions").onclick = e => { const b = e.target.closest("[data-opt]"); if(!b) return; fmtPick = +b.dataset.opt; renderOptions(); };
$("#dlStart").onclick = async () => {
  if(!fmtData) return;
  const o = fmtData.options[fmtPick];
  busy($("#dlStart"), true);
  try{
    const {job} = await post("/api/jobs", {mode:"download", url:fmtData.url, format:o.format, kind:o.kind,
      label:o.label, title:fmtData.title});
    if(fmtData.thumbnail) thumbsByTitle.set(fmtData.title, fmtData.thumbnail);
    activeDl.set(job, {id:job, title:fmtData.title, label:o.label, kind:o.kind, status:"running", step:"Shuru hocche", progress:null});
    toast(`Downloading ${o.label}`);
    dlSeg = "active"; tab("downloads"); pollDownloads();
  }catch(err){ toast(err.message); }
  busy($("#dlStart"), false);
};

/* ---------- download list (active jobs + saved files) ---------- */
const activeDl = new Map();   // job id -> job
let savedDl = [], dlPolling = false, dlSeg = "active";
async function refreshDownloads(){
  try{
    const [jobs, saved] = await Promise.all([api("/api/jobs?active=1"), api("/api/downloads")]);
    jobs.filter(j=>j.mode==="download").forEach(j=>activeDl.set(j.id, j));
    savedDl = saved;
  }catch(e){}
  if(!activeDl.size && savedDl.length && cur()==="downloads" && !refreshDownloads._seen) dlSeg = "done";
  refreshDownloads._seen = true;
  renderDownloads();
  if([...activeDl.values()].some(j=>j.status==="running")) pollDownloads();
}
async function pollDownloads(){
  if(dlPolling) return; dlPolling = true;
  while([...activeDl.values()].some(j=>j.status==="running")){
    let finished = false;
    for(const [id, old] of activeDl){
      if(old.status!=="running") continue;
      try{
        const j = await api("/api/jobs/"+id);
        activeDl.set(id, j);
        if(j.status==="done"){ activeDl.delete(id); finished = true; toast("Downloaded " + (j.result?.title || "")); }
      }catch(e){ activeDl.set(id, {...old, status:"error", error:e.message}); }
    }
    if(finished) savedDl = await api("/api/downloads").catch(()=>savedDl);
    renderDownloads();
    await sleep(1000);
  }
  dlPolling = false;
  renderDownloads();
}
const kindGlyph = d => d.kind==="audio" || d.label==="MP3" ? "music" : "film";
function dlTile(d){
  const th = thumbsByTitle.get(d.title);
  return `<span class="src-tile big">${icon(kindGlyph(d))}${th?`<img src="${esc(th)}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">`:""}</span>`;
}
function renderDownloads(){
  const act = [...activeDl.values()];
  const running = act.filter(j=>j.status==="running").length;
  const btn = $('#tabbar [data-tab="downloads"]');
  btn.querySelector(".badge")?.remove();
  if(running) btn.insertAdjacentHTML("beforeend", `<i class="badge">${running}</i>`);
  $("#dlActiveN").textContent = act.length; $("#dlDoneN").textContent = savedDl.length;
  $$("#dlSeg button").forEach(b=>b.classList.toggle("on", b.dataset.seg===dlSeg));

  let html;
  if(dlSeg==="active"){
    html = act.map(j=>{
      const p = j.progress;
      if(j.status==="running") return `
        <div class="row">${dlTile(j)}
          <div class="row-main"><b>${esc(j.title||"Video")}</b>
            <small>${esc(j.label||"")}${p&&p.total?` · ${fmtBytes(p.total)}`:""}${/^Download hocche/i.test(j.step||"") ? "" : ` · ${esc(stepEn(j.step))}`}</small>
            <div class="dl-prog"><div class="bar-track ${p&&p.pct!=null?"":"indet"}"><i style="--p:${p&&p.pct!=null?Math.min(100,p.pct)/100:0}"></i></div>
              <small class="num">${p&&p.pct!=null?Math.round(p.pct)+"%":""}</small></div>
          </div>
          <button class="round-btn" type="button" data-cancel="${j.id}" aria-label="Cancel download">${icon("x")}</button>
        </div>`;
      return `
        <div class="row">${dlTile(j)}
          <div class="row-main"><b>${esc(j.title||"Video")}</b>
            <small class="err">${j.status==="cancelled" ? "Cancelled" : esc(j.error||"Download failed")}</small></div>
          <button class="btn sm" type="button" data-dismiss="${j.id}">Dismiss</button>
        </div>`;
    }).join("") || `<div class="empty">${icon("download")}<b>Nothing downloading</b><small>Fetch a video link and pick a quality to start.</small></div>`;
  } else {
    html = savedDl.map(d=>`
      <div class="row tap" data-openrow="${d.id}">${dlTile(d)}
        <div class="row-main"><b>${esc(d.title)}</b>
          <small>${esc(d.label)} · ${fmtBytes(d.size)} · ${ago(d.created)}</small></div>
        <button class="icon-btn" type="button" data-dlmenu="${d.id}" aria-label="More">${icon("kebab")}</button>
      </div>`).join("") || `<div class="empty">${icon("film")}<b>No finished downloads</b><small>Videos you download are listed here.</small></div>`;
  }
  $("#dlList").innerHTML = html;
}
$("#dlSeg").onclick = e => { const b = e.target.closest("[data-seg]"); if(!b) return; dlSeg = b.dataset.seg; renderDownloads(); };
function openSaved(x){
  if(NATIVE) NATIVE.openFile(x.uri, x.mime); else openExternal(x.file_url);
}
async function deleteSaved(x, alsoFile){
  await api(`/api/downloads/${x.id}?file=${alsoFile?1:0}`, {method:"DELETE"}).catch(err=>toast(err.message));
  savedDl = savedDl.filter(y=>y.id!==x.id); renderDownloads();
  toast(alsoFile ? "File deleted" : "Removed from list");
}
function savedMenu(x){
  openMenu(x.title, [
    {icon:"play", label:"Open", fn:()=>openSaved(x)},
    NATIVE ? {icon:"share", label:"Share", fn:()=>NATIVE.shareFile(x.uri, x.mime)}
           : {icon:"download", label:"Save to this device", fn:()=>{ const a=document.createElement("a"); a.href=x.file_url+"?dl=1"; a.download=""; document.body.append(a); a.click(); a.remove(); }},
    {icon:"x", label:"Remove from list", fn:()=>deleteSaved(x, false)},
    {icon:"trash", label:NATIVE ? "Delete from phone" : "Delete file", danger:true, fn:()=>{ if(confirm("Delete this file? This can't be undone.")) deleteSaved(x, true); }},
  ]);
}
$("#dlList").onclick = async e => {
  const t = e.target.closest("button,[data-openrow]"); if(!t) return;
  const d = id => savedDl.find(x=>String(x.id)===String(id));
  if(t.dataset.cancel){
    t.disabled = true;
    post(`/api/jobs/${t.dataset.cancel}/cancel`).catch(err=>toast(err.message));
  } else if(t.dataset.dismiss){
    activeDl.delete(t.dataset.dismiss); renderDownloads();
  } else if(t.dataset.dlmenu){
    const x = d(t.dataset.dlmenu); if(x) savedMenu(x);
  } else if(t.dataset.openrow){
    const x = d(t.dataset.openrow); if(x) openSaved(x);
  }
};
$("#dlMore").onclick = () => openMenu("Downloads", [
  {icon:"x", label:"Clear finished list", fn:async ()=>{
    if(!savedDl.length) return toast("Nothing to clear");
    if(!confirm("Remove every finished download from this list? The files stay on your device.")) return;
    for(const x of savedDl) await api(`/api/downloads/${x.id}?file=0`, {method:"DELETE"}).catch(()=>{});
    savedDl = []; renderDownloads();
  }},
]);

/* ---------- history ---------- */
let histT, histFilter = "all";
$("#histSearch").oninput = () => { clearTimeout(histT); histT = setTimeout(loadHistory, 250); };
$("#histFilter").onclick = e => { const b = e.target.closest("[data-f]"); if(!b) return; histFilter = b.dataset.f; $$("#histFilter .chip").forEach(c=>c.classList.toggle("on", c===b)); loadHistory(); };
async function loadHistory(){
  const q = $("#histSearch").value.trim();
  const [rows, vids] = await Promise.all([
    histFilter==="videos" ? [] : api("/api/history?q="+encodeURIComponent(q)).catch(()=>[]),
    histFilter==="lists" ? [] : api("/api/downloads").catch(()=>[]),
  ]);
  window._hist = rows; savedDl = histFilter==="lists" ? savedDl : vids;
  const ql = q.toLowerCase();
  const all = [
    ...rows.map((r,i)=>({t:r.created, html:(()=>{ const s = sourceOf(r); return `
      <div class="row tap" data-i="${i}">${srcTile(s.glyph)}
        <div class="row-main"><b>${esc(r.title||"List")}</b><small>${plural(r.items.length,"item")} · ${esc(s.label)}</small></div>
        <button class="icon-btn" type="button" data-hmenu="${i}" aria-label="More">${icon("kebab")}</button>
      </div>`; })()})),
    ...vids.filter(v=>!ql || String(v.title).toLowerCase().includes(ql)).map(v=>({t:v.created, html:`
      <div class="row tap" data-openrow="${v.id}"><span class="src-tile">${icon(kindGlyph(v))}</span>
        <div class="row-main"><b>${esc(v.title)}</b><small>${esc(v.label)} ${v.kind==="audio"?"audio":"video"} · ${fmtBytes(v.size)}</small></div>
        <button class="icon-btn" type="button" data-dlmenu="${v.id}" aria-label="More">${icon("kebab")}</button>
      </div>`})),
  ].sort((a,b)=>b.t-a.t);
  let html = "", g = "";
  for(const x of all){ const gg = dayGroup(x.t); if(gg!==g){ g = gg; html += `<h3 class="group-label">${g}</h3>`; } html += x.html; }
  $("#histList").innerHTML = html ? `<div class="rows">${html}</div>` :
    `<div class="empty">${icon(q?"search":"history")}<b>${q ? "No matches" : "Nothing here yet"}</b><small>${q ? "Try a movie, tool or list name." : "Lists and downloads you make are kept here."}</small></div>`;
}
$("#histList").onclick = async e => {
  const t = e.target.closest("[data-hmenu],[data-dlmenu],[data-i],[data-openrow]"); if(!t) return;
  if(t.dataset.hmenu){
    const r = window._hist[+t.dataset.hmenu];
    openMenu(r.title, [
      {icon:"copy", label:"Copy list", fn:()=>copy(listText(r))},
      {icon:"trash", label:"Delete", danger:true, fn:async ()=>{ await api("/api/history/"+r.id,{method:"DELETE"}).catch(err=>toast(err.message)); loadHistory(); }},
    ]);
  } else if(t.dataset.dlmenu){
    const x = savedDl.find(v=>String(v.id)===t.dataset.dlmenu); if(x) savedMenu(x);
  } else if(t.dataset.openrow){
    const x = savedDl.find(v=>String(v.id)===t.dataset.openrow); if(x) openSaved(x);
  } else render(window._hist[+t.dataset.i]);
};
async function clearHistory(){
  if(!confirm("Delete your whole history? This can't be undone.")) return;
  await api("/api/history", {method:"DELETE"}).catch(err=>toast(err.message));
  toast("History cleared"); window._recent = [];
  if(cur()==="history") loadHistory();
}
$("#histMore").onclick = () => openMenu("History", [{icon:"trash", label:"Clear history", danger:true, fn:clearHistory}]);
$("#setClearHist").onclick = clearHistory;

/* ---------- share: link elo -> List naki Download? ---------- */
const shareDlg = $("#shareDlg");
function askShare(url){
  $("#shareUrl").textContent = url;
  shareDlg.dataset.url = url;
  shareDlg.showModal();
}
$("#shareList").onclick = () => { shareDlg.close(); $("#urlInput").value = shareDlg.dataset.url; startList({url:shareDlg.dataset.url}); };
$("#shareDl").onclick = () => { shareDlg.close(); openDownload(shareDlg.dataset.url); };
$("#shareClose").onclick = () => shareDlg.close();
shareDlg.addEventListener("click", e => { if(e.target===shareDlg) shareDlg.close(); });
window.onNativeShare = url => askShare(url);   // Android: app already khola thakle

/* ---------- theme ---------- */
function applyTheme(t){
  const sysDark = NATIVE && NATIVE.isNight ? (()=>{ try{ return NATIVE.isNight(); }catch(e){ return true; } })() : matchMedia("(prefers-color-scheme: dark)").matches;
  const eff = t==="system" ? (sysDark ? "dark" : "light") : t;
  document.documentElement.dataset.theme = eff;
  $("#themeValue").textContent = {system:"Auto (match the phone)", light:"Light", dark:"Dark"}[t] || "Auto";
  const bg = getComputedStyle(document.documentElement).getPropertyValue("--bg").trim();
  $$('meta[name="theme-color"]').forEach(m=>m.setAttribute("content", bg));
  if(NATIVE && NATIVE.setTheme){ try{ NATIVE.setTheme(eff==="light", bg); }catch(e){} }
}
const setThemeTo = t => { store.set("theme", t); applyTheme(t); };
$("#setTheme").onclick = () => { const t = store.get("theme","system"); const mark = k => k===t ? "check" : "moon";
  openMenu("Appearance", [
    {icon:t==="system"?"check":"phone", label:"Auto, match the phone", fn:()=>setThemeTo("system")},
    {icon:t==="light"?"check":"sun", label:"Light", fn:()=>setThemeTo("light")},
    {icon:t==="dark"?"check":"moon", label:"Dark", fn:()=>setThemeTo("dark")},
  ]); };
matchMedia("(prefers-color-scheme: dark)").addEventListener?.("change", () => applyTheme(store.get("theme","system")));
window.onNativeTheme = () => applyTheme(store.get("theme","system"));

/* ---------- settings ---------- */
const provider = () => ($('input[name="provider"]:checked') || {}).value || "gemini";
function syncProvider(){
  $$("[data-for]").forEach(d=>d.classList.toggle("hidden", d.dataset.for!==provider()));
  $$(".prov").forEach(l=>l.classList.toggle("on", l.querySelector("input").checked));   // purono WebView e :has() nai
}
$$('input[name="provider"]').forEach(r=>r.onchange = syncProvider);
const chip = (ok, name) => `<span class="pill ${ok?"ok":"bad"}">${icon(ok?"check":"x")}${esc(name)}</span>`;
async function loadSettings(){
  const s = await api("/api/settings");
  settingsCache = s;
  const r = $(`input[name="provider"][value="${s.provider}"]`); if(r) r.checked = true;
  $("#sModel").value = s.gemini_model;
  $("#sKey").value = ""; $("#sKey").placeholder = s.gemini_key_set ? `Saved: ${s.gemini_key_hint}` : "AIza…";
  $("#sOHost").value = s.ollama_host; $("#sOModel").value = s.ollama_model; $("#sOVision").value = s.ollama_vision_model;
  $("#sLang").value = s.tesseract_lang;
  $("#cookieState").innerHTML = s.cookies ? `${icon("check")}Added` : "Not added";
  $("#cookieState").className = "pill " + (s.cookies ? "ok" : "");
  $("#sName").value = store.get("name","");
  $("#ytdlErr").textContent = s.ytdl_error ? "Downloader error: " + s.ytdl_error : "";
  $("#ytdlErr").classList.toggle("hidden", !s.ytdl_error);
  const dlName = (s.downloader_label || "ffmpeg").replace(/ \d[\d.]*$/, "");
  $("#sysChips").innerHTML = chip(s.ytdl_ok ?? s.ffmpeg, dlName) + chip(s.tesseract, s.ocr_label || "Tesseract") + chip(s.provider!=="gemini" || s.gemini_key_set, "AI");
  syncProvider();
  renderHomeStatus(s);
  return s;
}
$("#settingsForm").onsubmit = async e => {
  e.preventDefault();
  const btn = $("#saveSettings"); busy(btn, true);
  store.set("name", $("#sName").value.trim()); renderHello();
  try{
    const s = await post("/api/settings", {
      provider:provider(), gemini_key:$("#sKey").value.trim(), gemini_model:$("#sModel").value.trim(),
      ollama_host:$("#sOHost").value.trim(), ollama_model:$("#sOModel").value.trim(),
      ollama_vision_model:$("#sOVision").value.trim(), tesseract_lang:$("#sLang").value.trim()});
    toast("Settings saved");
    if(s && "provider" in s) renderHomeStatus(s);
    loadSettings().catch(()=>{});
  }catch(err){ toast(err.message); }
  busy(btn, false);
};
$("#cookieFile").onchange = async e => {
  const f = e.target.files[0]; if(!f) return;
  const fd = new FormData(); fd.append("file", f);
  try{ await api("/api/cookies",{method:"POST",body:fd}); toast("Cookies saved"); loadSettings(); }
  catch(err){ toast(err.message); }
  e.target.value = "";
};
$("#setCookies").onclick = () => {
  const has = settingsCache && settingsCache.cookies;
  openMenu(NATIVE ? "Cookies let ListKoro open private posts. Logging in under Linked accounts saves them for you."
                  : "Export cookies.txt from facebook.com with the \"Get cookies.txt LOCALLY\" browser extension, then upload it here.", [
    {icon:"upload", label: has ? "Replace cookies.txt" : "Upload cookies.txt", fn:()=>$("#cookieFile").click()},
    ...(has ? [{icon:"trash", label:"Remove cookies", danger:true, fn:async ()=>{ await api("/api/cookies",{method:"DELETE"}).catch(err=>toast(err.message)); loadSettings().catch(()=>{}); toast("Cookies removed"); }}] : []),
  ]);
};
$("#setLogin").onclick = () => openMenu("Log in once and ListKoro saves the cookies. Your password is never stored.", [
  {icon:"c-facebook", label:"Log in to Facebook", fn:()=>NATIVE && NATIVE.login("facebook")},
  {icon:"c-instagram", label:"Log in to Instagram", fn:()=>NATIVE && NATIVE.login("instagram")},
]);
$("#setRecheck").onclick = async () => { await loadSettings().then(()=>toast("Status updated")).catch(e=>toast(e.message)); };
window.onNativeLogin = () => loadSettings().catch(()=>{});
$("#copyDebug").onclick = async () => {
  try{ const d = await api("/api/debug"); copy("ListKoro debug\n" + JSON.stringify(d, null, 1)); }
  catch(e){ toast(e.message); }
};
const appVersion = (()=>{ try{ return NATIVE && NATIVE.version ? NATIVE.version() : ""; }catch(e){ return ""; } })();
$("#appVersion").textContent = appVersion ? "Version " + appVersion : "Web version";

/* Advanced: height animate kore khole/bondho hoy */
$("#adv summary").onclick = e => {
  e.preventDefault();
  const d = $("#adv"), body = d.querySelector(".adv-body");
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches || !body.animate;
  if(still){ d.open = !d.open; return; }
  if(d.open){
    d.classList.add("closing");
    body.animate([{height:body.scrollHeight+"px", opacity:1},{height:"0px", opacity:0}], {duration:200, easing:"cubic-bezier(.2,.7,.2,1)"})
      .onfinish = () => { d.open = false; d.classList.remove("closing"); };
  } else {
    d.open = true;
    body.animate([{height:"0px", opacity:0},{height:body.scrollHeight+"px", opacity:1}], {duration:240, easing:"cubic-bezier(.2,.7,.2,1)"});
  }
};

/* About: shotti info /api/debug theke */
async function loadAbout(){
  const row = (ic, label, val) => `<div class="g-row"><svg class="i"><use href="#i-${ic}"/></svg><span class="g-label">${esc(label)}<small>${esc(val)}</small></span></div>`;
  try{
    const [d, s] = await Promise.all([api("/api/debug"), settingsCache ? settingsCache : api("/api/settings")]);
    const prov = PROVIDER_LABEL[s.provider] || s.provider;
    $("#aboutRows").innerHTML = [
      row("info", "ListKoro", appVersion ? "Version " + appVersion : "Web version, served by Python " + (d.python || "")),
      d.device ? row("phone", d.device, "Android " + (d.android || "")) : "",
      row("download", "yt-dlp", d.ytdl_version ? "Version " + d.ytdl_version : (d.ytdl_error || "Not ready")),
      row("scan", "Text recognition", s.ocr_label || (s.tesseract ? "Tesseract" : "Tesseract not installed")),
      row("sparkle", "AI provider", prov + (s.provider==="gemini" ? " · " + s.gemini_model : s.provider==="ollama" ? " · " + s.ollama_model : "")),
    ].join("");
  }catch(e){ $("#aboutRows").innerHTML = row("alert", "Couldn't load details", e.message); }
}
$("#aboutDebug").onclick = () => $("#copyDebug").click();

/* Home greeting: shudhu nam deya thakle */
function renderHello(){
  const n = store.get("name","").trim();
  $("#homeHello").textContent = n ? `Hey ${n}` : "";
  $("#homeHello").classList.toggle("hidden", !n);
}

addEventListener("scroll", () => document.body.classList.toggle("scrolled", scrollY > 8), {passive:true});

/* Keyboard khola thakle tab bar ar dock lukai, jate field dheke na jay */
document.addEventListener("focusin", e => { if(e.target.matches("input:not([type=radio]):not([type=file]),textarea")) document.body.classList.add("kb"); });
document.addEventListener("focusout", () => setTimeout(()=>{ if(!document.activeElement || !document.activeElement.matches("input,textarea")) document.body.classList.remove("kb"); }, 60));

/* ---------- onboarding ---------- */
function startOnboarding(){
  const ob = $("#onboard"), track = $("#obTrack"), dots = $$("#obDots i"), n = dots.length;
  ob.classList.remove("hidden");
  const idx = () => Math.round(track.scrollLeft / track.clientWidth);
  const sync = () => {
    const i = idx();
    dots.forEach((d,k)=>d.classList.toggle("on", k===i));
    $("#obNext span").textContent = i===n-1 ? "Get started" : "Next";
  };
  const done = () => { store.set("onboarded", "1"); ob.classList.add("hidden"); };
  track.addEventListener("scroll", () => requestAnimationFrame(sync), {passive:true});
  $("#obNext").onclick = () => { const i = idx(); if(i>=n-1) done(); else track.scrollTo({left:(i+1)*track.clientWidth, behavior:"smooth"}); };
  $("#obSkip").onclick = done;
  window._onboardOpen = () => !ob.classList.contains("hidden");
}

// Android back button: age dialog/menu bondho, tarpor ager screen
window.listkoroBack = () => {
  if(menuDlg.open){ menuDlg.close(); return true; }
  if(shareDlg.open){ shareDlg.close(); return true; }
  if(window._onboardOpen && window._onboardOpen()){ return false; }
  return back();
};
window.onNativeDownloadsChanged = () => refreshDownloads();

/* ---------- boot ---------- */
(async function boot(){
  applyTheme(store.get("theme","system"));
  renderHello();
  const q = new URLSearchParams(location.search);
  const t = store.get("tab","home");
  stack = [TABS.includes(t) && t!=="settings" ? t : "home"];
  show(stack[0], 0);
  if(!q.get("share") && !q.get("job") && !store.get("onboarded")) startOnboarding();
  else store.set("onboarded","1");
  if(q.get("share")){ history.replaceState(null,"","/"); askShare(q.get("share")); }
  if(q.get("job")) watchList(q.get("job"));
  else {
    // app abar khulle: cholte thaka list job dekhao
    const jobs = await api("/api/jobs?active=1").catch(()=>[]);
    const lj = jobs.find(j=>j.mode==="list");
    if(lj) watchList(lj.id);
  }
  refreshDownloads();
  api("/api/settings").then(renderHomeStatus).catch(()=>{});
  if(!NATIVE && "serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(()=>{});
})();
