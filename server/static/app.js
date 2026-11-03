const $ = s => document.querySelector(s);
const NATIVE = window.ListKoroApp || null;   // Android app er bhitore chalale
if (NATIVE) document.documentElement.classList.add("native");
const TYPE_LABEL = {movie:"Movie",series:"Series",anime:"Anime",tool:"AI Tool",app:"App",website:"Website",book:"Book",other:"Other"};
let current = null, pollTimer = null, images = [];

function toast(msg){const t=$("#toast");t.textContent=msg;t.classList.add("show");clearTimeout(t._h);t._h=setTimeout(()=>t.classList.remove("show"),1800);}
async function api(path, opts={}){
  const r = await fetch(path, opts);
  const data = await r.json().catch(()=>({error:"Server reply bujha gelo na"}));
  if(!r.ok) throw new Error(data.error || r.statusText);
  return data;
}
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

/* ---------- tabs ---------- */
function showTab(name){
  document.querySelectorAll(".tabs button").forEach(b=>b.classList.toggle("active", b.dataset.tab===name));
  ["link","image","text","history"].forEach(n=>$("#tab-"+n).classList.toggle("hidden", n!==name));
  if(name==="history") loadHistory();
  try{localStorage.setItem("tab",name)}catch(e){}
}
document.querySelectorAll(".tabs button").forEach(b=>b.onclick=()=>showTab(b.dataset.tab));

/* ---------- jobs ---------- */
function setBusy(on){
  document.querySelectorAll(".btn.primary.big").forEach(b=>b.disabled=on && b.id!=="saveSettings");
  $("#status").classList.toggle("hidden", !on);
}
function showError(msg){ $("#error").textContent = "⚠️ " + msg; $("#error").classList.remove("hidden"); }

async function startJob(body){
  $("#error").classList.add("hidden"); $("#result").classList.add("hidden");
  setBusy(true); $("#statusStep").textContent = "Shuru hocche…";
  try{
    const opts = body instanceof FormData ? {method:"POST", body} :
      {method:"POST", headers:{"Content-Type":"application/json; charset=utf-8"}, body:JSON.stringify(body)};
    const {job} = await api("/api/jobs", opts);
    poll(job);
  }catch(e){ setBusy(false); showError(e.message); }
}

function poll(job){
  const t0 = Date.now();
  setBusy(true);
  history.replaceState(null, "", "/?job="+job);
  clearInterval(pollTimer);
  const tick = async () => {
    $("#statusTime").textContent = Math.round((Date.now()-t0)/1000) + "s";
    try{
      const j = await api("/api/jobs/"+job);
      $("#statusStep").textContent = j.step + "…";
      if(j.status === "done"){ clearInterval(pollTimer); setBusy(false); history.replaceState(null,"","/"); render(j.result); }
      else if(j.status === "error"){ clearInterval(pollTimer); setBusy(false); history.replaceState(null,"","/"); showError(j.error); }
    }catch(e){ clearInterval(pollTimer); setBusy(false); history.replaceState(null,"","/"); showError(e.message); }
  };
  tick(); pollTimer = setInterval(tick, 1500);
}

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

/* ---------- link ---------- */
$("#pasteBtn").onclick = async () => {
  if(NATIVE){ const v = NATIVE.paste().trim(); if(v) $("#urlInput").value = v; else toast("Clipboard khali"); return; }
  try{ $("#urlInput").value = (await navigator.clipboard.readText()).trim(); }
  catch(e){ toast("Clipboard permission dao, ba long-press kore paste koro"); }
};
$("#goLink").onclick = () => {
  const v = $("#urlInput").value.trim();
  if(!v) return toast("Link dao");
  startJob({url:v});
};
$("#urlInput").onkeydown = e => { if(e.key==="Enter") $("#goLink").click(); };

/* ---------- image ---------- */
$("#imgInput").onchange = e => {
  images = [...e.target.files].slice(0,10);
  $("#thumbs").innerHTML = images.map(f=>`<img src="${URL.createObjectURL(f)}" alt="">`).join("");
};
$("#goImage").onclick = () => {
  if(!images.length) return toast("Age image select koro");
  const fd = new FormData();
  images.forEach(f=>fd.append("image", f));
  fd.append("text", $("#imgCaption").value);
  startJob(fd);
};

/* ---------- text ---------- */
$("#goText").onclick = () => {
  const v = $("#textInput").value.trim();
  if(!v) return toast("Text paste koro");
  startJob({text:v});
};

/* ---------- history ---------- */
let histT;
$("#histSearch").oninput = () => { clearTimeout(histT); histT = setTimeout(loadHistory, 250); };
async function loadHistory(){
  const rows = await api("/api/history?q="+encodeURIComponent($("#histSearch").value.trim())).catch(()=>[]);
  window._hist = rows;
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
  if(del){ e.stopPropagation(); if(confirm("Delete korbo?")){ await api("/api/history/"+del.dataset.del,{method:"DELETE"}); loadHistory(); } return; }
  const it = e.target.closest(".hist-item"); if(it) render(window._hist[+it.dataset.i]);
};

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
    await api("/api/settings",{method:"POST",headers:{"Content-Type":"application/json; charset=utf-8"},body:JSON.stringify({
      provider:$("#sProvider").value, gemini_key:$("#sKey").value.trim(), gemini_model:$("#sModel").value.trim(),
      ollama_host:$("#sOHost").value.trim(), ollama_model:$("#sOModel").value.trim(),
      ollama_vision_model:$("#sOVision").value.trim(), tesseract_lang:$("#sLang").value.trim()})});
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
// Android back button: age dialog bondho
window.listkoroBack = () => { if(dlg.open){ dlg.close(); return true; } return false; };
$("#copyDebug").onclick = async () => {
  try{ const d = await api("/api/debug"); copy("ListKoro debug\n" + JSON.stringify(d, null, 1)); }
  catch(e){ toast(e.message); }
};
$("#cookieDel").onclick = async () => { await api("/api/cookies",{method:"DELETE"}); loadSettings(); toast("Removed"); };

/* ---------- boot ---------- */
(function boot(){
  let tab = "link"; try{ tab = localStorage.getItem("tab") || "link"; }catch(e){}
  if(tab==="history") tab="link";
  showTab(tab);
  const job = new URLSearchParams(location.search).get("job");
  if(job) poll(job);
  api("/api/settings").then(s=>{ if(s.provider==="gemini" && !s.gemini_key_set) showError("Prothome Settings (⚙️) e free Gemini API key dao, othoba provider 'Basic OCR'/'Ollama' select koro."); }).catch(()=>{});
  if(!NATIVE && "serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(()=>{});
})();
