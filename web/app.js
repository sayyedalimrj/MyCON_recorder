const $ = (id) => document.getElementById(id);
const panels = [...document.querySelectorAll(".panel")];
const navs = [...document.querySelectorAll(".nav")];

function go(id){
  panels.forEach(p=>p.classList.toggle("active",p.id===id));
  navs.forEach(n=>n.classList.toggle("active",n.dataset.go===id));
  scrollTo({top:0,behavior:"smooth"});
}
document.querySelectorAll("[data-go]").forEach(el=>el.addEventListener("click",()=>go(el.dataset.go)));

function fmt(n){
  const v = Number(n);
  if(!Number.isFinite(v)) return "0";
  return v.toFixed(4).replace(/0+$/,"").replace(/\.$/,"");
}
function value(id){ return $(id).value.trim(); }
function markerData(){
  return {
    project:value("project"),anchor:value("anchor"),crs:value("crs"),
    x:Number(value("x")),y:Number(value("y")),z:Number(value("z")),
    mount:value("mount").toUpperCase(),azimuth_deg:Number(value("azimuth")),
    size_mm:Number(value("sizeMm")),floor:value("floor"),
    model_id:value("modelId"),model_size_m:Number(value("modelSize"))
  };
}
function canonical(d){
  let s=[
    "v=1","project="+d.project,"anchor="+d.anchor,"crs="+d.crs,
    "x="+fmt(d.x),"y="+fmt(d.y),"z="+fmt(d.z),
    "mount="+d.mount.toUpperCase(),"azimuth_deg="+fmt(d.azimuth_deg),
    "size_mm="+fmt(d.size_mm),"floor="+d.floor
  ].join("&");
  if(d.model_id) s += "&model_id="+d.model_id+"&model_size_m="+fmt(d.model_size_m||1);
  return s;
}
async function sig12(text){
  const hash=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(text));
  return [...new Uint8Array(hash)].slice(0,6).map(v=>v.toString(16).padStart(2,"0")).join("");
}
async function makePayload(d){
  const sig=await sig12(canonical(d));
  const p=new URLSearchParams();
  [["project",d.project],["anchor",d.anchor],["crs",d.crs],["x",fmt(d.x)],["y",fmt(d.y)],["z",fmt(d.z)],
   ["mount",d.mount.toUpperCase()],["azimuth_deg",fmt(d.azimuth_deg)],["size_mm",fmt(d.size_mm)],["floor",d.floor]]
   .forEach(([k,v])=>p.append(k,v));
  if(d.model_id){p.append("model_id",d.model_id);p.append("model_size_m",fmt(d.model_size_m||1));}
  p.append("sig",sig);
  return {payload:"mycon://anchor/v1?"+p.toString(),sig,canonical:canonical(d)};
}
async function renderMarker(){
  const d=markerData();
  if(!d.project||!d.anchor||!d.crs||!Number.isFinite(d.size_mm)||d.size_mm<=0){
    $("markerStatus").textContent="ورودی ناقص"; $("markerStatus").className="badge bad"; return;
  }
  const out=await makePayload(d);
  $("payload").value=out.payload;
  $("signatureLine").textContent="sig="+out.sig+" • "+out.canonical;
  $("markerStatus").textContent="معتبر"; $("markerStatus").className="badge ok";
  const canvas=$("qrCanvas");
  if(window.QRCode?.toCanvas){
    await QRCode.toCanvas(canvas,out.payload,{width:512,margin:4,errorCorrectionLevel:"M",color:{dark:"#000000",light:"#ffffff"}});
  }else{
    const c=canvas.getContext("2d");c.fillStyle="#fff";c.fillRect(0,0,512,512);c.fillStyle="#111";c.font="18px sans-serif";c.fillText("QR library unavailable",120,255);
  }
  return {...d,...out};
}
$("generateMarker").addEventListener("click",async(e)=>{e.preventDefault();await renderMarker();});
$("markerForm").addEventListener("change",()=>renderMarker().catch(()=>{}));
$("copyPayload").addEventListener("click",async()=>{await navigator.clipboard.writeText($("payload").value);});
$("downloadQr").addEventListener("click",async()=>{
  await renderMarker(); const a=document.createElement("a"); a.download=(value("project")+"_"+value("anchor")+".png").replace(/[^a-zA-Z0-9_.-]+/g,"_"); a.href=$("qrCanvas").toDataURL("image/png");a.click();
});
$("downloadJson").addEventListener("click",async()=>{
  const d=await renderMarker(); if(!d)return;
  const blob=new Blob([JSON.stringify(d,null,2)],{type:"application/json"});const a=document.createElement("a");a.download=(d.project+"_"+d.anchor+".json").replace(/[^a-zA-Z0-9_.-]+/g,"_");a.href=URL.createObjectURL(blob);a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);
});
function saved(){try{return JSON.parse(localStorage.getItem("mycon.markers.v1")||"[]")}catch{return[]}}
function drawSaved(){
  const host=$("savedMarkers"), list=saved(); host.innerHTML="";
  if(!list.length){host.textContent="هنوز کنترلی ذخیره نشده.";host.className="stack empty";return}
  host.className="stack";
  list.slice().reverse().forEach((d)=>{
    const el=document.createElement("div");el.className="saved";
    el.innerHTML="<div><strong></strong><span></span></div><button class='secondary small'>باز کردن</button>";
    el.querySelector("strong").textContent=d.project+" / "+d.anchor;
    el.querySelector("span").textContent=d.crs+" • "+fmt(d.x)+", "+fmt(d.y)+", "+fmt(d.z);
    el.querySelector("button").onclick=()=>{
      [["project",d.project],["anchor",d.anchor],["crs",d.crs],["x",d.x],["y",d.y],["z",d.z],["mount",d.mount],["azimuth",d.azimuth_deg],["sizeMm",d.size_mm],["floor",d.floor||""],["modelId",d.model_id||""],["modelSize",d.model_size_m||1]].forEach(([k,v])=>$(k).value=v);
      go("markerPanel");renderMarker();
    };
    host.appendChild(el);
  });
}
$("saveMarker").addEventListener("click",async(e)=>{
  e.preventDefault();const d=await renderMarker();if(!d)return;
  const list=saved().filter(x=>!(x.project===d.project&&x.anchor===d.anchor));list.push(d);localStorage.setItem("mycon.markers.v1",JSON.stringify(list.slice(-50)));drawSaved();
});
$("clearSaved").addEventListener("click",()=>{localStorage.removeItem("mycon.markers.v1");drawSaved();});

function metric(label,value,cls=""){
  const e=document.createElement("article");e.className="card metric "+cls;e.innerHTML="<div class='label'></div><div class='value'></div>";e.querySelector(".label").textContent=label;e.querySelector(".value").textContent=value;return e;
}
function checkRow(name,ok,detail=""){
  const e=document.createElement("div");e.className="check";
  const left=document.createElement("div");left.innerHTML="<strong></strong><div class='muted mini'></div>";left.querySelector("strong").textContent=name;left.querySelector("div").textContent=detail;
  const dot=document.createElement("span");dot.className="dot "+(ok?"ok":"");e.append(left,dot);return e;
}
async function inspectZip(file){
  const msg=$("inspectMessage"); msg.textContent="در حال خواندن بسته…";
  if(!window.JSZip){msg.textContent="JSZip در دسترس نیست.";return}
  try{
    const zip=await JSZip.loadAsync(file);
    const names=Object.keys(zip.files);
    const read=async(name)=>zip.file(name)?zip.file(name).async("text"):null;
    const stext=await read("session.json"); if(!stext)throw new Error("session.json پیدا نشد");
    const manifest=JSON.parse(stext);
    const r4text=await read("r4_compatibility.json");
    const poseText=await read("pose.csv");
    const qrText=await read("qr_events.jsonl");
    $("manifestView").textContent=JSON.stringify(manifest,null,2);

    const qa=$("qaGrid");qa.innerHTML="";
    qa.append(metric("Frames",String(manifest.frame_count??"—")));
    qa.append(metric("Tracking",manifest.tracking_ratio!=null?(Number(manifest.tracking_ratio)*100).toFixed(1)+"%":"—"));
    qa.append(metric("Observed FPS",manifest.observed_frame_rate_fps!=null?Number(manifest.observed_frame_rate_fps).toFixed(1):"—"));
    qa.append(metric("Valid QR",String(manifest.valid_qr_event_count??(qrText?qrText.trim().split(/\r?\n/).filter(Boolean).length:"—"))));

    const checks=[
      ["MYCON_CAPTURE_SESSION",manifest.format==="MYCON_CAPTURE_SESSION","format="+(manifest.format??"missing")],
      ["format_version=1",Number(manifest.format_version)===1,"version="+(manifest.format_version??"missing")],
      ["Video",names.includes("arcore_recording.mp4"),"arcore_recording.mp4"],
      ["Pose",!!poseText,"pose.csv"],
      ["IMU",names.includes("imu.csv"),"imu.csv"],
      ["QR events",!!qrText,"qr_events.jsonl"],
      ["R4 camera",names.includes("r4_camera.json"),"r4_camera.json"],
      ["R4 controls",names.includes("r4_controls.json"),"r4_controls.json"],
      ["R4 contract",!!r4text,"r4_compatibility.json"],
      ["Integrity",names.includes("integrity_sha256.json"),"integrity_sha256.json"]
    ];
    const compat=$("compatList");compat.innerHTML="";checks.forEach(c=>compat.append(checkRow(...c)));
    const pass=checks.every(c=>c[1]);$("compatBadge").textContent=pass?"READY":"CHECK";$("compatBadge").className="badge "+(pass?"ok":"warn");
    msg.textContent=file.name+" • "+(file.size/1024/1024).toFixed(1)+" MB • "+names.length+" entries";
  }catch(err){
    msg.textContent="خطا: "+err.message;$("compatBadge").textContent="ERROR";$("compatBadge").className="badge bad";
  }
}
$("zipInput").addEventListener("change",e=>{const f=e.target.files?.[0];if(f)inspectZip(f)});
function network(){const on=navigator.onLine;$("onlineBadge").textContent=on?"آنلاین":"آفلاین";$("onlineBadge").className="badge "+(on?"ok":"warn")}
addEventListener("online",network);addEventListener("offline",network);
if("serviceWorker"in navigator)addEventListener("load",()=>navigator.serviceWorker.register("./service-worker.js").catch(()=>{}));
drawSaved();network();renderMarker().catch(()=>{});
