const $ = s => document.querySelector(s);
const NATIVE = window.ListKoroApp || null;   // Android app er bhitore chalale
if (NATIVE) document.documentElement.classList.add("native");
const TYPE_LABEL = {movie:"Movie",series:"Series",anime:"Anime",tool:"AI Tool",app:"App",website:"Website",book:"Book",other:"Other"};
const JSONH = {"Content-Type":"application/json; charset=utf-8"};
let current = null, images = [];

function toast(msg){const t=$("#toast");t.textContent=msg;t.classList.add("show");clearTimeout(t._h);t._h=setTimeout(()=>t.classList.remove("show"),2000);}
async function api(path, opts={}){
  const r = await fetch(path, opts);
  const data = await r.json().catch(()=>({error:"Server reply bujha gelo na"}));
  if(!r.ok) throw new Error(data.error || r.statusText);
  return data;
}
const post = (path, body) => api(path, {method:"POST", headers:JSONH, body:JSON.stringify(body||{})});
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const sleep = ms => new Promise(r => setTimeout(r, ms));

function fmtBytes(b){
  if(!b && b!==0) return "";
  const u=["B","KB","MB","GB"]; let i=0; while(b>=1024 && i<u.length-1){ b/=1024; i++; }
  return (b>=100||i===0 ? Math.round(b) : b.toFixed(1)) + " " + u[i];
}
function fmtSecs(s){
  if(s==null || !isFinite(s)) return "";
  s = Math.round(s); return s>=60 ? `${Math.floor(s/60)}m ${s%60}s` : `${s}s`;
}
function progressText(p){
  if(!p) return "";
  const parts = [];
  if(p.pct!=null) parts.push(`${p.pct}%`);
  if(p.total) parts.push(`${fmtBytes(p.done)} / ${fmtBytes(p.total)}`); else if(p.done) parts.push(fmtBytes(p.done));
  if(p.speed) parts.push(`${fmtBytes(p.speed)}/s`);
  if(p.eta!=null && p.eta>0) parts.push(`${fmtSecs(p.eta)} baki`);
  return parts.join(" · ");
}
function setBar(el, p){
  el.classList.toggle("indet", !p || p.pct==null);
  el.querySelector("i").style.width = p && p.pct!=null ? Math.min(100, p.pct) + "%" : "";
}

/* ---------- pages ---------- */
function showPage(name){
  document.querySelectorAll("#bottomNav button").forEach(b=>b.classList.toggle("active", b.dataset.page===name));
  ["list","download","history"].forEach(n=>$("#page-"+n).classList.toggle("hidden", n!==name));
  if(name==="history") loadHistory();
  if(name==="download") refreshDownloads();
  try{ localStorage.setItem("page", name); }catch(e){}
  window.scrollTo({top:0});
}
document.querySelectorAll("#bottomNav button").forEach(b=>b.onclick=()=>showPage(b.dataset.page));

function showSub(name){
  document.querySelectorAll(".seg button").forEach(b=>b.classList.toggle("active", b.dataset.sub===name));
  ["link","image","text"].forEach(n=>$("#tab-"+n).classList.toggle("hidden", n!==name));
  try{ localStorage.setItem("sub", name); }catch(e){}
}
document.querySelectorAll(".seg button").forEach(b=>b.onclick=()=>showSub(b.dataset.sub));

/* ---------- list job (step log + progress + cancel) ---------- */
let listJob = null;
function showError(msg){ $("#error").textContent = "⚠️ " + msg; $("#error").classList.remove("hidden"); }

async function startList(body){
  $("#error").classList.add("hidden"); $("#result").classList.add("hidden");
  try{
    const opts = body instanceof FormData ? {method:"POST", body} : {method:"POST", headers:JSONH, body:JSON.stringify(body)};
    const {job} = await api("/api/jobs", opts);
    watchList(job);
  }catch(e){ showError(e.message); }
}

function renderJobCard(j){
  const card = $("#job");
  card.classList.remove("hidden");
  card.classList.toggle("finished", j.status!=="running");
  $("#jobStep").textContent = j.status==="running" ? j.step + "…" : j.step;
  $("#jobTime").textContent = fmtSecs(Date.now()/1000 - j.created) || "0s";
  const p = j.progress;
  $("#jobBar").classList.toggle("hidden", !p || j.status!=="running");
  $("#jobBarText").classList.toggle("hidden", !p || j.status!=="running");
  if(p){ setBar($("#jobBar"), p); $("#jobBarText").textContent = progressText(p); }
  const log = j.log || [];
  $("#jobLog").innerHTML = log.map((l,i)=>{
    const last = i===log.length-1;
    const cls = last && j.status==="running" ? "now" : (last && j.status!=="done" ? "bad" : "");
    return `<li class="${cls}"><span>${esc(l.msg)}</span><small>${fmtSecs(l.t)}</small></li>`;
  }).join("");
  $("#jobCancel").classList.toggle("hidden", j.status!=="running");
}

async function watchList(job){
  listJob = job;
  showPage("list");
  ["#goLink","#goImage","#goText"].forEach(id=>$(id).disabled=true);
  history.replaceState(null, "", "/?job="+job);
  while(listJob===job){
    let j;
    try{ j = await api("/api/jobs/"+job); }
    catch(e){ showError(e.message); break; }
    renderJobCard(j);
    if(j.status==="done"){ render(j.result); break; }
    if(j.status==="error"){ showError(j.error); break; }
    if(j.status==="cancelled"){ toast("Cancel hoyeche"); break; }
    await sleep(1000);
  }
  if(listJob===job){ listJob = null; history.replaceState(null,"","/"); }
  ["#goLink","#goImage","#goText"].forEach(id=>$(id).disabled=false);
}
$("#jobCancel").onclick = async () => {
  if(!listJob) return;
  $("#jobCancel").disabled = true;
  try{ await post(`/api/jobs/${listJob}/cancel`); }catch(e){ toast(e.message); }
  $("#jobCancel").disabled = false;
};

/* ---------- result ---------- */
function searchUrl(it){
  const extra = {movie:"movie",series:"series",anime:"anime",tool:"AI tool",app:"app",website:"",book:"book"}[it.type] ?? "";
  return "https://www.google.com/search?q=" + encodeURIComponent([it.name, it.year, extra].filter(Boolean).join(" "));
}
function render(r){
  current = r;
  $("#resTitle").textContent = r.title || "List";
  const d = new Date(r.created*1000);
  $("#resMeta").textContent = `${r.items.length} ta item · ${r.provider} · ${d.toLocaleString()}`;
  $("#resWarn").textContent = r.warning ? "⚠️ " + r.warning : "";
  $("#resWarn").classList.toggle("hidden", !r.warning);
  $("#resDownload").classList.toggle("hidden", !(r.source_type==="url" && /^https?:/.test(r.source||"")));
  $("#resList").innerHTML = r.items.map((it,i)=>`
    <li>
      <div class="it-name">${esc(it.name)}${it.year?` <span class="yr">(${esc(it.year)})</span>`:""}</div>
      <div class="it-act">
        <a href="${searchUrl(it)}" target="_blank" rel="noopener" aria-label="Search">🔎</a>
        <button data-copy="${i}" aria-label="Copy">📋</button>
      </div>
      <div class="it-sub">
        <span class="tag" style="--c:var(--t-${it.type})">${TYPE_LABEL[it.type]||it.type}</span>
        ${it.timestamp?`<span>⏱ ${esc(it.timestamp)}</span>`:""}
        ${it.note?`<span>${esc(it.note)}</span>`:""}
      </div>
    </li>`).join("");
  showPage("list");
  $("#result").classList.remove("hidden");
  $("#result").scrollIntoView({behavior:"smooth", block:"start"});
}
$("#resList").onclick = e => {
  const b = e.target.closest("[data-copy]"); if(!b) return;
  copy(current.items[+b.dataset.copy].name);
};
function listText(r){
  return (r.title?r.title+"\n":"") + r.items.map((it,i)=>`${i+1}. ${it.name}${it.year?` (${it.year})`:""}${it.note?` - ${it.note}`:""}`).join("\n");
}
async function copy(text){
  if(NATIVE){ NATIVE.copy(text); toast("Copied ✓"); return; }
  try{ await navigator.clipboard.writeText(text); }
  catch(e){ const ta=document.createElement("textarea"); ta.value=text; document.body.append(ta); ta.select(); document.execCommand("copy"); ta.remove(); }
  toast("Copied ✓");
}
$("#copyAll").onclick = () => current && copy(listText(current));
$("#shareAll").onclick = async () => {
  if(!current) return;
  if(NATIVE) return NATIVE.share(listText(current));
  if(navigator.share){ try{ await navigator.share({title:current.title, text:listText(current)}); }catch(e){} }
  else copy(listText(current));
};
$("#resDownload").onclick = () => current && openDownload(current.source);

/* ---------- paste helper ---------- */
async function pasteInto(input){
  if(NATIVE){ const v = NATIVE.paste().trim(); if(v) input.value = v; else toast("Clipboard khali"); return; }
  try{ input.value = (await navigator.clipboard.readText()).trim(); }
  catch(e){ toast("Clipboard permission dao, ba long-press kore paste koro"); }
}

/* ---------- list inputs ---------- */
$("#pasteBtn").onclick = () => pasteInto($("#urlInput"));
$("#goLink").onclick = () => {
  const v = $("#urlInput").value.trim();
  if(!v) return toast("Link dao");
  startList({url:v});
};
$("#urlInput").onkeydown = e => { if(e.key==="Enter") $("#goLink").click(); };
$("#imgInput").onchange = e => {
  images = [...e.target.files].slice(0,10);
  $("#thumbs").innerHTML = images.map(f=>`<img src="${URL.createObjectURL(f)}" alt="">`).join("");
};
$("#goImage").onclick = () => {
  if(!images.length) return toast("Age image select koro");
  const fd = new FormData();
  images.forEach(f=>fd.append("image", f));
  fd.append("text", $("#imgCaption").value);
  startList(fd);
};
$("#goText").onclick = () => {
  const v = $("#textInput").value.trim();
  if(!v) return toast("Text paste koro");
  startList({text:v});
};

/* ---------- download: formats ---------- */
let fmtJob = null, fmtData = null;
function openDownload(url){
  showPage("download");
  $("#dlUrl").value = url;
  $("#dlGo").click();
}
$("#dlPaste").onclick = () => pasteInto($("#dlUrl"));
$("#dlUrl").onkeydown = e => { if(e.key==="Enter") $("#dlGo").click(); };
$("#dlGo").onclick = async () => {
  const url = $("#dlUrl").value.trim();
  if(!url) return toast("Link dao");
  $("#dlFormats").classList.add("hidden");
  $("#dlGo").disabled = true;
  try{
    const {job} = await post("/api/jobs", {mode:"formats", url});
    fmtJob = job;
    $("#dlStatus").classList.remove("hidden");
    while(fmtJob===job){
      const j = await api("/api/jobs/"+job);
      $("#dlStep").textContent = j.step + (j.status==="running" ? "…" : "");
      $("#dlTime").textContent = fmtSecs(Date.now()/1000 - j.created) || "0s";
      if(j.status==="done"){ showFormats(j.result); break; }
      if(j.status==="error"){ toast("Error"); showDlError(j.error); break; }
      if(j.status==="cancelled") break;
      await sleep(800);
    }
  }catch(e){ showDlError(e.message); }
  if(fmtJob){ fmtJob = null; }
  $("#dlStatus").classList.add("hidden");
  $("#dlGo").disabled = false;
};
$("#dlStatusCancel").onclick = async () => {
  const job = fmtJob; fmtJob = null;
  $("#dlStatus").classList.add("hidden"); $("#dlGo").disabled = false;
  if(job) post(`/api/jobs/${job}/cancel`).catch(()=>{});
};
function showDlError(msg){
  $("#dlFormats").classList.remove("hidden");
  $("#dlThumb").src = ""; $("#dlTitle").textContent = "⚠️ Video info pawa gelo na";
  $("#dlMeta").textContent = msg; $("#dlOptions").innerHTML = "";
}
function showFormats(r){
  fmtData = r;
  $("#dlFormats").classList.remove("hidden");
  $("#dlThumb").src = r.thumbnail || "";
  $("#dlTitle").textContent = r.title || "Video";
  $("#dlMeta").textContent = [r.uploader, r.duration ? fmtSecs(r.duration) : ""].filter(Boolean).join(" · ");
  $("#dlOptions").innerHTML = r.options.map((o,i)=>`
    <button class="fmt ${o.kind}" data-opt="${i}" type="button">
      <b>${o.kind==="audio" ? "🎵 " : "🎬 "}${esc(o.label)}</b>
      <small>${o.size ? "~" + fmtBytes(o.size) : (o.kind==="audio" ? "audio" : "video")}</small>
    </button>`).join("");
}
$("#dlOptions").onclick = async e => {
  const b = e.target.closest("[data-opt]"); if(!b || !fmtData) return;
  const o = fmtData.options[+b.dataset.opt];
  try{
    const {job} = await post("/api/jobs", {mode:"download", url:fmtData.url, format:o.format, kind:o.kind,
      label:o.label, title:fmtData.title});
    activeDl.set(job, {id:job, title:fmtData.title, label:o.label, kind:o.kind, status:"running", step:"Shuru hocche", progress:null});
    toast(`${o.label} download shuru ✓`);
    renderDownloads(); pollDownloads();
  }catch(err){ toast(err.message); }
};

/* ---------- download list (active jobs + saved files) ---------- */
const activeDl = new Map();   // job id -> job
let savedDl = [], dlPolling = false;
async function refreshDownloads(){
  try{
    const [jobs, saved] = await Promise.all([api("/api/jobs?active=1"), api("/api/downloads")]);
    jobs.filter(j=>j.mode==="download").forEach(j=>activeDl.set(j.id, j));
    savedDl = saved;
  }catch(e){}
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
        if(j.status==="done"){ activeDl.delete(id); finished = true; toast("Download shesh ✓ " + (j.result?.title || "")); }
      }catch(e){ activeDl.set(id, {...old, status:"error", error:e.message}); }
    }
    if(finished) savedDl = await api("/api/downloads").catch(()=>savedDl);
    renderDownloads();
    await sleep(1000);
  }
  dlPolling = false;
  renderDownloads();
}
function renderDownloads(){
  const running = [...activeDl.values()].filter(j=>j.status==="running").length;
  const btn = document.querySelector('#bottomNav [data-page="download"]');
  btn.querySelector(".badge")?.remove();
  if(running) btn.insertAdjacentHTML("beforeend", `<i class="badge">${running}</i>`);

  const act = [...activeDl.values()].map(j=>{
    const p = j.progress;
    const icon = j.kind==="audio" || j.label==="MP3" ? "🎵" : "🎬";
    if(j.status==="running") return `
      <div class="dl">
        <div class="dl-top"><span class="dl-ic">${icon}</span>
          <div class="dl-info"><b>${esc(j.title||"Video")}</b><small>${esc(j.label||"")} · ${esc(j.step)}…</small></div></div>
        <div class="bar ${p&&p.pct!=null?"":"indet"}"><i style="width:${p&&p.pct!=null?Math.min(100,p.pct):""}%"></i></div>
        <small class="bar-text">${esc(progressText(p)) || "Shuru hocche…"}</small>
        <div class="dl-act"><button class="btn ghost danger" data-cancel="${j.id}">✕ Cancel</button></div>
      </div>`;
    return `
      <div class="dl">
        <div class="dl-top"><span class="dl-ic">${icon}</span>
          <div class="dl-info"><b>${esc(j.title||"Video")}</b>
          <small class="err">${j.status==="cancelled" ? "Cancel kora hoyeche" : "⚠️ " + esc(j.error||"Error")}</small></div></div>
        <div class="dl-act"><button class="btn ghost" data-dismiss="${j.id}">Thik ache</button></div>
      </div>`;
  }).join("");
  const done = savedDl.map(d=>`
    <div class="dl">
      <div class="dl-top"><span class="dl-ic">${d.kind==="audio"?"🎵":"🎬"}</span>
        <div class="dl-info"><b>${esc(d.title)}</b>
        <small>${esc(d.label)} · ${fmtBytes(d.size)} · ${new Date(d.created*1000).toLocaleDateString()}</small>
        <small>${esc(d.location || d.filename)}</small></div></div>
      <div class="dl-act">
        ${NATIVE ? `<button class="btn ghost" data-open="${d.id}">▶ Open</button>
                    <button class="btn ghost" data-sharefile="${d.id}">↗ Share</button>`
                 : `<a class="btn ghost" href="${d.file_url}" target="_blank" rel="noopener">▶ Open</a>
                    <a class="btn ghost" href="${d.file_url}?dl=1" download>💾 Save</a>`}
        <button class="btn ghost danger" data-deldl="${d.id}">🗑</button>
      </div>
    </div>`).join("");
  $("#dlList").innerHTML = act + done || `<div class="empty">Ekhono kichu download hoy nai</div>`;
  $("#dlClear").classList.toggle("hidden", !savedDl.length);
}
$("#dlList").onclick = async e => {
  const t = e.target.closest("button"); if(!t) return;
  const d = id => savedDl.find(x=>String(x.id)===id);
  if(t.dataset.cancel){
    t.disabled = true; t.textContent = "Cancel hocche…";
    post(`/api/jobs/${t.dataset.cancel}/cancel`).catch(err=>toast(err.message));
  } else if(t.dataset.dismiss){
    activeDl.delete(t.dataset.dismiss); renderDownloads();
  } else if(t.dataset.open){
    const x = d(t.dataset.open); if(x && NATIVE) NATIVE.openFile(x.uri, x.mime);
  } else if(t.dataset.sharefile){
    const x = d(t.dataset.sharefile); if(x && NATIVE) NATIVE.shareFile(x.uri, x.mime);
  } else if(t.dataset.deldl){
    const alsoFile = confirm("Phone theke file tao delete korbo?\n\nOK = file soho delete\nCancel = shudhu list theke muche felo");
    await api(`/api/downloads/${t.dataset.deldl}?file=${alsoFile?1:0}`, {method:"DELETE"}).catch(err=>toast(err.message));
    savedDl = savedDl.filter(x=>String(x.id)!==t.dataset.deldl); renderDownloads();
  }
};
$("#dlClear").onclick = async () => {
  if(!confirm("Download list theke sob muche felbo? (Phone er file thakbe)")) return;
  for(const x of savedDl) await api(`/api/downloads/${x.id}?file=0`, {method:"DELETE"}).catch(()=>{});
  savedDl = []; renderDownloads();
};

/* ---------- history ---------- */
let histT;
$("#histSearch").oninput = () => { clearTimeout(histT); histT = setTimeout(loadHistory, 250); };
async function loadHistory(){
  const rows = await api("/api/history?q="+encodeURIComponent($("#histSearch").value.trim())).catch(()=>[]);
  window._hist = rows;
  $("#histClear").classList.toggle("hidden", !rows.length || !!$("#histSearch").value.trim());
  $("#histList").innerHTML = rows.length ? rows.map((r,i)=>`
    <div class="hist-item" data-i="${i}">
      <div><b>${esc(r.title||"List")}</b>
      <small>${r.items.length} ta · ${esc(r.items.slice(0,4).map(x=>x.name).join(", "))}</small>
      <small>${new Date(r.created*1000).toLocaleDateString()} · ${esc(r.source)}</small></div>
      <button class="hist-del" data-del="${r.id}" aria-label="Delete">🗑</button>
    </div>`).join("") : `<div class="empty">Ekhono kichu nai</div>`;
}
$("#histList").onclick = async e => {
  const del = e.target.closest("[data-del]");
  if(del){ e.stopPropagation(); if(confirm("Ei list ta delete korbo?")){ await api("/api/history/"+del.dataset.del,{method:"DELETE"}); loadHistory(); } return; }
  const it = e.target.closest(".hist-item"); if(it) render(window._hist[+it.dataset.i]);
};
$("#histClear").onclick = async () => {
  if(!confirm("Pura history delete korbo? Eta ar ferot ana jabe na.")) return;
  await api("/api/history", {method:"DELETE"}).catch(err=>toast(err.message));
  toast("History khali ✓"); loadHistory();
};

/* ---------- share: link elo -> List naki Download? ---------- */
const shareDlg = $("#shareDlg");
function askShare(url){
  $("#shareUrl").textContent = url;
  shareDlg.dataset.url = url;
  shareDlg.showModal();
}
$("#shareList").onclick = () => { shareDlg.close(); $("#urlInput").value = shareDlg.dataset.url; showSub("link"); startList({url:shareDlg.dataset.url}); };
$("#shareDl").onclick = () => { shareDlg.close(); openDownload(shareDlg.dataset.url); };
$("#shareClose").onclick = () => shareDlg.close();
window.onNativeShare = url => askShare(url);   // Android: app already khola thakle

/* ---------- settings ---------- */
const dlg = $("#settings");
function syncProvider(){ document.querySelectorAll("[data-for]").forEach(d=>d.classList.toggle("hidden", d.dataset.for!==$("#sProvider").value)); }
$("#sProvider").onchange = syncProvider;
async function loadSettings(){
  const s = await api("/api/settings");
  $("#sProvider").value = s.provider; $("#sModel").value = s.gemini_model;
  $("#sKey").value = ""; $("#sKey").placeholder = s.gemini_key_set ? "Saved: "+s.gemini_key_hint+" (change korte notun dao)" : "AIza…";
  $("#sOHost").value = s.ollama_host; $("#sOModel").value = s.ollama_model; $("#sOVision").value = s.ollama_vision_model;
  $("#sLang").value = s.tesseract_lang;
  $("#cookieState").textContent = s.cookies ? "✓ cookies ache" : "cookies nai";
  $("#cookieState").className = "pill " + (s.cookies?"ok":"");
  const chip = (ok,name)=>`<span class="pill ${ok?"ok":"bad"}">${ok?"✓":"✕"} ${name}</span>`;
  $("#ytdlErr").textContent = s.ytdl_error ? "Downloader error: " + s.ytdl_error : "";
  $("#ytdlErr").classList.toggle("hidden", !s.ytdl_error);
  $("#sysChips").innerHTML = chip(s.ytdl_ok ?? s.ffmpeg, s.downloader_label||"ffmpeg") + chip(s.tesseract, s.ocr_label||"tesseract") + chip(s.provider!=="gemini"||s.gemini_key_set,"AI ready");
  syncProvider();
  return s;
}
$("#openSettings").onclick = async () => { await loadSettings().catch(e=>toast(e.message)); dlg.showModal(); };
$("#saveSettings").onclick = async e => {
  e.preventDefault();
  try{
    await post("/api/settings", {
      provider:$("#sProvider").value, gemini_key:$("#sKey").value.trim(), gemini_model:$("#sModel").value.trim(),
      ollama_host:$("#sOHost").value.trim(), ollama_model:$("#sOModel").value.trim(),
      ollama_vision_model:$("#sOVision").value.trim(), tesseract_lang:$("#sLang").value.trim()});
    toast("Saved ✓"); dlg.close();
  }catch(err){ toast(err.message); }
};
$("#cookieFile").onchange = async e => {
  const f = e.target.files[0]; if(!f) return;
  const fd = new FormData(); fd.append("file", f);
  try{ await api("/api/cookies",{method:"POST",body:fd}); toast("Cookies saved ✓"); loadSettings(); }
  catch(err){ toast(err.message); }
  e.target.value = "";
};
$("#loginFb").onclick = () => NATIVE && NATIVE.login("facebook");
$("#loginIg").onclick = () => NATIVE && NATIVE.login("instagram");
window.onNativeLogin = () => loadSettings().catch(()=>{});
$("#copyDebug").onclick = async () => {
  try{ const d = await api("/api/debug"); copy("ListKoro debug\n" + JSON.stringify(d, null, 1)); }
  catch(e){ toast(e.message); }
};
$("#cookieDel").onclick = async () => { await api("/api/cookies",{method:"DELETE"}); loadSettings(); toast("Removed"); };

// Android back button: age dialog bondho, tarpor List page e ferot
window.listkoroBack = () => {
  if(shareDlg.open){ shareDlg.close(); return true; }
  if(dlg.open){ dlg.close(); return true; }
  if($("#page-list").classList.contains("hidden")){ showPage("list"); return true; }
  return false;
};
window.onNativeDownloadsChanged = () => refreshDownloads();

/* ---------- boot ---------- */
(async function boot(){
  let page = "list", sub = "link";
  try{ page = localStorage.getItem("page") || "list"; sub = localStorage.getItem("sub") || "link"; }catch(e){}
  showSub(sub);
  showPage(page==="history" ? "list" : page);
  const q = new URLSearchParams(location.search);
  if(q.get("share")){ history.replaceState(null,"","/"); askShare(q.get("share")); }
  if(q.get("job")) watchList(q.get("job"));
  else {
    // app abar khulle: cholte thaka list job dekhao
    const jobs = await api("/api/jobs?active=1").catch(()=>[]);
    const lj = jobs.find(j=>j.mode==="list");
    if(lj) watchList(lj.id);
  }
  refreshDownloads();
  api("/api/settings").then(s=>{ if(s.provider==="gemini" && !s.gemini_key_set) showError("Prothome Settings (⚙️) e free Gemini API key dao, othoba provider 'Basic OCR'/'Ollama' select koro."); }).catch(()=>{});
  if(!NATIVE && "serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(()=>{});
})();
