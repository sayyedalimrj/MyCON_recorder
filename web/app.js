const $ = (id) => document.getElementById(id);
const panels = [...document.querySelectorAll(".panel")];
const navs = [...document.querySelectorAll(".nav")];
const panelIds = new Set(panels.map(p=>p.id));

function toast(message,tone="default"){
  let host=document.querySelector(".toast-host");
  if(!host){
    host=document.createElement("div");
    host.className="toast-host";
    host.setAttribute("aria-live","polite");
    document.body.appendChild(host);
  }
  const item=document.createElement("div");
  item.className="toast "+tone;
  item.textContent=message;
  host.appendChild(item);
  requestAnimationFrame(()=>item.classList.add("show"));
  setTimeout(()=>{
    item.classList.remove("show");
    setTimeout(()=>item.remove(),180);
  },1800);
}

function tapFeedback(){
  try{navigator.vibrate?.(8)}catch{}
}

const THEME_KEY="mycon.theme.v1";
const themeMedia=window.matchMedia?.("(prefers-color-scheme: dark)");
function resolvedTheme(mode){
  if(mode==="light"||mode==="dark")return mode;
  return themeMedia?.matches?"dark":"light";
}
function applyThemeMode(mode,{persist=false,announce=false}={}){
  const safe=["system","light","dark"].includes(mode)?mode:"system";
  const resolved=resolvedTheme(safe);
  document.documentElement.dataset.themeMode=safe;
  document.documentElement.dataset.theme=resolved;
  document.documentElement.style.colorScheme=resolved;
  const meta=document.querySelector('meta[name="theme-color"]');
  if(meta)meta.content=resolved==="dark"?"#07111f":"#f5f7fb";
  const button=$("themeToggle");
  if(button){
    const labels={system:"سیستم",light:"روشن",dark:"تیره"};
    button.title="نمایش: "+labels[safe];
    button.setAttribute("aria-label","حالت نمایش: "+labels[safe]);
  }
  if(persist)localStorage.setItem(THEME_KEY,safe);
  if(announce){
    const labels={system:"مطابق سیستم",light:"حالت روشن",dark:"حالت تیره"};
    toast(labels[safe],"ok");
  }
}
function cycleTheme(){
  const current=document.documentElement.dataset.themeMode||"system";
  const next=current==="system"?"light":current==="light"?"dark":"system";
  tapFeedback();
  applyThemeMode(next,{persist:true,announce:true});
}
applyThemeMode(localStorage.getItem(THEME_KEY)||"system");
$("themeToggle")?.addEventListener("click",cycleTheme);
themeMedia?.addEventListener?.("change",()=>{
  if((document.documentElement.dataset.themeMode||"system")==="system")applyThemeMode("system");
});

function go(id,opts={}){
  if(!panelIds.has(id))id="homePanel";
  const {hash=true,smooth=true}=opts;
  panels.forEach(p=>p.classList.toggle("active",p.id===id));
  navs.forEach(n=>{
    const active=n.dataset.go===id;
    n.classList.toggle("active",active);
    if(active)n.setAttribute("aria-current","page");
    else n.removeAttribute("aria-current");
  });
  if(hash && location.hash!=="#"+id)history.replaceState(null,"","#"+id);
  scrollTo({top:0,behavior:smooth?"smooth":"auto"});
}
document.querySelectorAll("[data-go]").forEach(el=>el.addEventListener("click",()=>{
  tapFeedback();
  go(el.dataset.go);
}));
window.addEventListener("hashchange",()=>go(location.hash.slice(1),{hash:false,smooth:false}));

function downloadBlob(blob,name){
  const a=document.createElement("a");
  a.href=URL.createObjectURL(blob);
  a.download=name;
  a.click();
  setTimeout(()=>URL.revokeObjectURL(a.href),1500);
}
function safeName(s){return String(s||"MYCON").replace(/[^a-zA-Z0-9_.-]+/g,"_");}
function fmt(n){
  const v=Number(n);
  if(!Number.isFinite(v))return"0";
  return v.toFixed(4).replace(/0+$/,"").replace(/\.$/,"");
}
function value(id){return $(id).value.trim();}
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
  if(d.model_id)s+="&model_id="+d.model_id+"&model_size_m="+fmt(d.model_size_m||1);
  return s;
}
async function sha256Hex(data){
  const bytes=data instanceof ArrayBuffer?data:new TextEncoder().encode(data);
  const hash=await crypto.subtle.digest("SHA-256",bytes);
  return [...new Uint8Array(hash)].map(v=>v.toString(16).padStart(2,"0")).join("");
}
async function sig12(text){return (await sha256Hex(text)).slice(0,12);}
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
    $("markerStatus").textContent="ورودی ناقص";$("markerStatus").className="soft-badge bad";return;
  }
  const out=await makePayload(d);
  $("payload").value=out.payload;
  $("signatureLine").textContent="sig="+out.sig+" • "+out.canonical;
  $("markerStatus").textContent="معتبر";$("markerStatus").className="soft-badge ok";
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
$("copyPayload").addEventListener("click",async()=>{
  try{
    await navigator.clipboard.writeText($("payload").value);
    tapFeedback();toast("Payload کپی شد","ok");
  }catch{
    toast("کپی انجام نشد","bad");
  }
});
$("downloadQr").addEventListener("click",async()=>{
  await renderMarker();
  const a=document.createElement("a");
  a.download=safeName(value("project")+"_"+value("anchor"))+".png";
  a.href=$("qrCanvas").toDataURL("image/png");
  a.click();
  tapFeedback();toast("QR آماده شد","ok");
});
$("downloadJson").addEventListener("click",async()=>{
  const d=await renderMarker();if(!d)return;
  downloadBlob(new Blob([JSON.stringify(d,null,2)],{type:"application/json"}),safeName(d.project+"_"+d.anchor)+".json");
  toast("JSON آماده شد","ok");
});
$("printQr").addEventListener("click",async()=>{
  const popup=window.open("","_blank");
  const d=await renderMarker();
  if(!d||!popup)return;
  const png=$("qrCanvas").toDataURL("image/png");
  const size=Number(d.size_mm)||160;
  popup.document.write(`<!doctype html><html><head><meta charset="utf-8"><title>MYCON ${d.project} ${d.anchor}</title>
  <style>@page{size:A4;margin:15mm}body{font-family:Arial,sans-serif;color:#000}.sheet{text-align:center}.qr{width:${size}mm;height:${size}mm;image-rendering:pixelated}.bar{width:100mm;border-top:1.2mm solid #000;margin:18mm auto 2mm}.meta{font-family:monospace;font-size:10pt;word-break:break-all}.warn{font-weight:bold;margin:8mm}</style></head>
  <body><div class="sheet"><h2>MYCON • ${d.project} / ${d.anchor}</h2><div class="warn">PRINT 100% / ACTUAL SIZE — QR symbol = ${size} mm</div><img class="qr" src="${png}"><div class="bar"></div><div>100 mm verification bar</div><p class="meta">${d.payload.replaceAll("&","&amp;")}</p></div><script>onload=()=>print()<\/script></body></html>`);
  popup.document.close();
});

function saved(){try{return JSON.parse(localStorage.getItem("mycon.markers.v1")||"[]")}catch{return[]}}
function drawSaved(){
  const host=$("savedMarkers"),list=saved();host.innerHTML="";
  if(!list.length){host.textContent="هنوز کنترلی ذخیره نشده.";host.className="stack empty";return}
  host.className="stack";
  list.slice().reverse().forEach(d=>{
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
  const list=saved().filter(x=>!(x.project===d.project&&x.anchor===d.anchor));
  list.push(d);localStorage.setItem("mycon.markers.v1",JSON.stringify(list.slice(-50)));drawSaved();
  tapFeedback();toast("کنترل ذخیره شد","ok");
});
$("clearSaved").addEventListener("click",()=>{localStorage.removeItem("mycon.markers.v1");drawSaved();});

function metric(label,val,cls=""){
  const e=document.createElement("article");e.className="card metric "+cls;
  e.innerHTML="<div class='label'></div><div class='value'></div>";
  e.querySelector(".label").textContent=label;e.querySelector(".value").textContent=val;return e;
}
function checkRow(name,ok,detail=""){
  const e=document.createElement("div");e.className="check";
  const left=document.createElement("div");left.innerHTML="<strong></strong><div class='muted mini'></div>";
  left.querySelector("strong").textContent=name;left.querySelector("div").textContent=detail;
  const dot=document.createElement("span");dot.className="dot "+(ok?"ok":"");e.append(left,dot);return e;
}
let currentQA=null;
function qaHistory(){try{return JSON.parse(localStorage.getItem("mycon.qa.history.v1")||"[]")}catch{return[]}}
function drawQaHistory(){
  const host=$("qaHistory"),list=qaHistory();host.innerHTML="";
  if(!list.length){host.textContent="هنوز بسته‌ای بررسی نشده.";host.className="stack empty";return}
  host.className="stack";
  list.slice().reverse().forEach(x=>{
    const el=document.createElement("div");el.className="saved";
    el.innerHTML="<div><strong></strong><span></span></div><span class='badge'></span>";
    el.querySelector("strong").textContent=x.file;
    el.querySelector("span:not(.badge)").textContent=x.platform+" • "+x.frames+" frames • "+x.date;
    const b=el.querySelector(".badge");b.textContent=x.score+"/100";b.className="soft-badge "+(x.score>=80?"ok":x.score>=55?"warn":"bad");
    host.appendChild(el);
  });
}
$("clearQaHistory").addEventListener("click",()=>{localStorage.removeItem("mycon.qa.history.v1");drawQaHistory();});

async function inspectZip(file){
  const msg=$("inspectMessage");msg.textContent="در حال خواندن بسته…";
  $("exportQa").disabled=true;
  if(!window.JSZip){msg.textContent="JSZip در دسترس نیست.";return}
  try{
    const zip=await JSZip.loadAsync(file);
    const names=Object.keys(zip.files);
    const read=async(name)=>zip.file(name)?zip.file(name).async("text"):null;
    const stext=await read("session.json");if(!stext)throw new Error("session.json پیدا نشد");
    const manifest=JSON.parse(stext);
    const r4text=await read("r4_compatibility.json");
    const r4=r4text?JSON.parse(r4text):null;
    const poseText=await read("pose.csv");
    const qrText=await read("qr_events.jsonl");
    const depthText=await read("depth_summary.json");
    const depthSummary=depthText?JSON.parse(depthText):(manifest.depth_capture||null);
    const integrityText=await read("integrity_sha256.json");
    const integrity=integrityText?JSON.parse(integrityText):null;
    $("manifestView").textContent=JSON.stringify(manifest,null,2);

    const controls=Number(manifest.valid_qr_event_count??(qrText?qrText.trim().split(/\r?\n/).filter(Boolean).length:0));
    const tracking=Number(manifest.tracking_ratio??0);
    const fps=Number(manifest.observed_frame_rate_fps??0);
    const frames=Number(manifest.frame_count??0);
    const depthSamples=Number(depthSummary?.sample_count??0);

    const checks=[
      ["Capture schema",manifest.format==="MYCON_CAPTURE_SESSION","MYCON_CAPTURE_SESSION"],
      ["Format version",Number(manifest.format_version)===1,"v1"],
      ["Video",names.includes("arcore_recording.mp4"),"arcore_recording.mp4"],
      ["Pose",!!poseText,"pose.csv"],
      ["IMU",names.includes("imu.csv"),"imu.csv"],
      ["QR events",!!qrText,"qr_events.jsonl"],
      ["R4 camera",names.includes("r4_camera.json"),"mycon.r4.camera.v1"],
      ["R4 controls",names.includes("r4_controls.json"),"mycon.r4.capture_controls.v1"],
      ["R4 contract",r4?.schema==="mycon.r4.capture_compatibility.v1",r4?.schema||"missing"],
      ["Bridge tool",names.includes("tools/mycon_r4_bridge.py"),"tools/mycon_r4_bridge.py"],
      ["Integrity",integrity?.schema==="mycon.capture.integrity.v1",integrity?.schema||"missing"]
    ];
    if(depthSummary?.enabled||depthSummary?.supported){
      checks.push(["Depth index",names.includes("depth/depth_index.jsonl"),depthSamples+" samples"]);
    }

    let score=0;
    score+=checks.filter(c=>c[1]).length/checks.length*45;
    score+=Math.min(25,Math.max(0,tracking)*25);
    score+=fps>=20?15:fps>=10?8:0;
    score+=controls>=7?15:controls>=4?12:controls>=1?5:0;
    score=Math.round(score);

    const qa=$("qaGrid");qa.innerHTML="";
    qa.append(metric("QA Score",score+"/100",score>=80?"good-metric":score>=55?"warn-metric":"bad-metric"));
    qa.append(metric("Frames",String(frames||"—")));
    qa.append(metric("Tracking",manifest.tracking_ratio!=null?(tracking*100).toFixed(1)+"%":"—"));
    qa.append(metric("Observed FPS",fps?fps.toFixed(1):"—"));
    qa.append(metric("Valid QR",String(controls)));
    qa.append(metric("Depth",depthSamples?String(depthSamples):"—"));
    qa.append(metric("Platform",String(manifest.platform||manifest.tracking_provider||"Android")));
    qa.append(metric("R4 Stage 8",controls>=4?"FIT READY":"NEEDS QR"));

    const compat=$("compatList");compat.innerHTML="";
    checks.forEach(c=>compat.append(checkRow(...c)));
    const pass=checks.every(c=>c[1]);
    $("compatBadge").textContent=pass?"READY":"CHECK";
    $("compatBadge").className="soft-badge "+(pass?"ok":"warn");
    msg.textContent=file.name+" • "+(file.size/1024/1024).toFixed(1)+" MB • "+names.length+" entries";

    currentQA={
      schema:"mycon.web.qa_report.v1",
      generated_utc:new Date().toISOString(),
      file:file.name,size_bytes:file.size,entries:names.length,
      score,checks:checks.map(([name,ok,detail])=>({name,ok,detail})),
      metrics:{frames,tracking_ratio:tracking,observed_fps:fps,valid_qr:controls,depth_samples:depthSamples},
      manifest,r4_compatibility:r4
    };
    $("exportQa").disabled=false;

    const hist=qaHistory();
    hist.push({file:file.name,score,platform:String(manifest.platform||manifest.tracking_provider||"Android"),frames,date:new Date().toLocaleString()});
    localStorage.setItem("mycon.qa.history.v1",JSON.stringify(hist.slice(-30)));
    drawQaHistory();
  }catch(err){
    msg.textContent="خطا: "+err.message;$("compatBadge").textContent="ERROR";$("compatBadge").className="soft-badge bad";
  }
}
$("zipInput").addEventListener("change",e=>{const f=e.target.files?.[0];if(f)inspectZip(f)});
$("exportQa").addEventListener("click",()=>{
  if(!currentQA)return;
  downloadBlob(new Blob([JSON.stringify(currentQA,null,2)],{type:"application/json"}),safeName(currentQA.file)+".qa.json");
  toast("گزارش QA آماده شد","ok");
});

let modelObjectURL=null;
let measurementMode=false;
let measurementPoints=[];
let builtinModels=[];

async function loadBuiltinCatalog(){
  const select=$("builtinModelSelect");
  const message=$("modelSourceMessage");
  if(!select)return;
  try{
    const response=await fetch("./models/catalog.json",{cache:"no-cache"});
    if(!response.ok)throw new Error("catalog "+response.status);
    const catalog=await response.json();
    builtinModels=Array.isArray(catalog.models)?catalog.models:[];
    select.innerHTML="";
    if(!builtinModels.length){
      select.innerHTML='<option value="">مدلی موجود نیست</option>';
      if(message)message.textContent="فهرست مدل‌ها خالی است.";
      return;
    }
    builtinModels.forEach((model,index)=>{
      const option=document.createElement("option");
      option.value=model.id;
      option.textContent=model.name;
      if(index===0)option.selected=true;
      select.appendChild(option);
    });
    if(message)message.textContent=builtinModels.length+" مدل آماده";
  }catch(err){
    builtinModels=[{
      id:"calibration-cube-1m",
      name:"مکعب کالیبراسیون ۱ متر",
      file:"./models/calibration-cube-1m.gltf",
      description:"مدل نمونه MyCON"
    }];
    select.innerHTML='<option value="calibration-cube-1m">مکعب کالیبراسیون ۱ متر</option>';
    if(message)message.textContent="فهرست محلی آماده است.";
  }
}

function openBuiltinModel(){
  const select=$("builtinModelSelect");
  const model=builtinModels.find(x=>x.id===select?.value);
  if(!model)return;
  resetModelMeasure();
  $("modelDimensions").textContent="ابعاد: —";
  if(modelObjectURL){
    URL.revokeObjectURL(modelObjectURL);
    modelObjectURL=null;
  }
  const viewer=$("modelViewer");
  viewer.removeAttribute("ios-src");
  viewer.setAttribute("src",model.file);
  $("modelMessage").textContent=model.name+" • GitHub";
  $("modelSourceMessage").textContent=model.description||"مدل آماده MyCON";
  tapFeedback();
  toast("مدل بارگذاری شد","ok");
}
$("loadBuiltinModel")?.addEventListener("click",openBuiltinModel);
loadBuiltinCatalog();

function resetModelMeasure(){
  measurementMode=false;
  measurementPoints=[];
  $("measureResult").textContent="—";
  $("measureModel").textContent="Measure 2 points";
  const viewer=$("modelViewer");
  [...viewer.querySelectorAll("[slot^='hotspot-measure-']")].forEach(x=>x.remove());
}

function addMeasureHotspot(point,index){
  const viewer=$("modelViewer");
  const dot=document.createElement("button");
  dot.className="measure-hotspot";
  dot.slot="hotspot-measure-"+index;
  dot.dataset.position=point.position.toString();
  dot.dataset.normal=point.normal.toString();
  dot.setAttribute("aria-label","Measurement point "+index);
  viewer.appendChild(dot);
}

$("modelInput").addEventListener("change",e=>{
  const file=e.target.files?.[0];if(!file)return;
  resetModelMeasure();
  if($("modelSourceMessage"))$("modelSourceMessage").textContent="مدل محلی • بدون آپلود";
  $("modelDimensions").textContent="ابعاد: —";
  if(modelObjectURL)URL.revokeObjectURL(modelObjectURL);
  modelObjectURL=URL.createObjectURL(file);
  const viewer=$("modelViewer");
  const ext=file.name.split(".").pop().toLowerCase();

  if(ext==="usdz"){
    viewer.removeAttribute("src");
    viewer.setAttribute("ios-src",modelObjectURL);
    $("modelMessage").textContent=file.name+" • USDZ آماده Quick Look روی iPhone";
  }else{
    viewer.setAttribute("src",modelObjectURL);
    viewer.removeAttribute("ios-src");
    $("modelMessage").textContent=file.name+" • "+(file.size/1024/1024).toFixed(1)+" MB • Local object URL";
  }
});

$("modelViewer").addEventListener("load",()=>{
  const viewer=$("modelViewer");
  if(typeof viewer.getDimensions==="function"){
    const d=viewer.getDimensions();
    $("modelDimensions").textContent=
      "X "+d.x.toFixed(3)+"m • Y "+d.y.toFixed(3)+"m • Z "+d.z.toFixed(3)+"m";
  }
});

$("measureModel").addEventListener("click",()=>{
  measurementMode=true;
  measurementPoints=[];
  $("measureResult").textContent="نقطه 1 را انتخاب کن";
  $("measureModel").textContent="در حال اندازه‌گیری…";
  [...$("modelViewer").querySelectorAll("[slot^='hotspot-measure-']")].forEach(x=>x.remove());
});
$("resetMeasure").addEventListener("click",resetModelMeasure);

$("modelViewer").addEventListener("click",e=>{
  if(!measurementMode)return;
  const viewer=$("modelViewer");
  if(typeof viewer.positionAndNormalFromPoint!=="function"){
    $("measureResult").textContent="Measurement API unavailable";
    measurementMode=false;
    return;
  }
  const hit=viewer.positionAndNormalFromPoint(e.clientX,e.clientY);
  if(!hit)return;
  measurementPoints.push(hit);
  addMeasureHotspot(hit,measurementPoints.length);

  if(measurementPoints.length===1){
    $("measureResult").textContent="نقطه 2 را انتخاب کن";
    return;
  }

  const a=measurementPoints[0].position;
  const b=measurementPoints[1].position;
  const dx=a.x-b.x,dy=a.y-b.y,dz=a.z-b.z;
  const dist=Math.sqrt(dx*dx+dy*dy+dz*dz);
  $("measureResult").textContent=
    dist<1 ? (dist*1000).toFixed(1)+" mm" : dist.toFixed(4)+" m";
  $("measureModel").textContent="Measure again";
  measurementMode=false;
});

let mediaStream=null,mediaRecorder=null,mediaChunks=[],motionRows=[],geoRows=[],webStartedAt=0,webTimer=null,geoWatch=null,lastWebPackage=null,lastWebPackageName="";
function chooseMime(){
  const options=["video/mp4","video/webm;codecs=vp9","video/webm;codecs=vp8","video/webm"];
  return options.find(x=>window.MediaRecorder?.isTypeSupported?.(x))||"";
}
function onMotion(e){
  if(!webStartedAt)return;
  const a=e.accelerationIncludingGravity||{};
  const r=e.rotationRate||{};
  motionRows.push([
    performance.now(),a.x??"",a.y??"",a.z??"",r.alpha??"",r.beta??"",r.gamma??"",e.interval??""
  ]);
  $("motionCount").textContent=String(motionRows.length);
}
async function startWebCapture(){
  const msg=$("webCaptureMessage");
  try{
    if(typeof DeviceMotionEvent!=="undefined"&&typeof DeviceMotionEvent.requestPermission==="function"){
      const p=await DeviceMotionEvent.requestPermission();
      if(p!=="granted")msg.textContent="Motion permission داده نشد؛ ویدئو ادامه پیدا می‌کند.";
    }
    mediaStream=await navigator.mediaDevices.getUserMedia({
      video:{facingMode:{ideal:"environment"},width:{ideal:1920},height:{ideal:1080},frameRate:{ideal:30,max:60}},
      audio:false
    });
    $("cameraPreview").srcObject=mediaStream;
    const track=mediaStream.getVideoTracks()[0];
    const settings=track.getSettings?.()||{};
    $("cameraMode").textContent=(settings.width&&settings.height)?settings.width+"×"+settings.height:"Camera";
    const mime=chooseMime();
    mediaRecorder=new MediaRecorder(mediaStream,mime?{mimeType:mime}:{});
    mediaChunks=[];motionRows=[];geoRows=[];lastWebPackage=null;lastWebPackageName="";
    mediaRecorder.ondataavailable=e=>{if(e.data?.size)mediaChunks.push(e.data);};
    mediaRecorder.start(1000);
    webStartedAt=performance.now();
    window.addEventListener("devicemotion",onMotion);
    if(navigator.geolocation){
      geoWatch=navigator.geolocation.watchPosition(pos=>{
        geoRows.push([Date.now(),pos.coords.latitude,pos.coords.longitude,pos.coords.altitude??"",pos.coords.accuracy??"",pos.coords.heading??"",pos.coords.speed??""]);
        $("geoCount").textContent=String(geoRows.length);
      },()=>{}, {enableHighAccuracy:true,maximumAge:1000,timeout:10000});
    }
    $("startWebCapture").disabled=true;$("stopWebCapture").disabled=false;$("downloadWebCapture").disabled=true;
    $("webCaptureState").textContent="REC";$("webCaptureState").className="live-pill bad";
    webTimer=setInterval(()=>{
      const sec=Math.floor((performance.now()-webStartedAt)/1000);
      $("webCaptureTimer").textContent=String(Math.floor(sec/60)).padStart(2,"0")+":"+String(sec%60).padStart(2,"0");
    },250);
    msg.textContent="Capture فعال است. این حالت fallback است و Pose متریک 6DoF ندارد.";
  }catch(err){
    msg.textContent="Camera شروع نشد: "+err.message;
  }
}
async function stopWebCapture(){
  if(!mediaRecorder)return;
  const done=new Promise(resolve=>mediaRecorder.addEventListener("stop",resolve,{once:true}));
  mediaRecorder.stop();await done;
  clearInterval(webTimer);webTimer=null;
  window.removeEventListener("devicemotion",onMotion);
  if(geoWatch!=null){navigator.geolocation.clearWatch(geoWatch);geoWatch=null;}
  mediaStream?.getTracks().forEach(t=>t.stop());
  $("cameraPreview").srcObject=null;
  const mime=mediaRecorder.mimeType||chooseMime()||"video/webm";
  const ext=mime.includes("mp4")?"mp4":"webm";
  const videoBlob=new Blob(mediaChunks,{type:mime});
  const project=$("webProject").value.trim()||"MYCON_PROJECT";
  const durationMs=Math.round(performance.now()-webStartedAt);
  const manifest={
    format:"MYCON_WEB_FALLBACK_CAPTURE",format_version:1,project_hint:project,
    started_utc:new Date(Date.now()-durationMs).toISOString(),ended_utc:new Date().toISOString(),
    duration_ms:durationMs,video_dataset:"web_recording."+ext,
    scientific_6dof:false,metric_pose_available:false,
    not_for_r4_pose_validation:true,
    sensors:{device_motion_samples:motionRows.length,gnss_samples:geoRows.length},
    warning:"Browser capture does not expose ARKit/ARCore metric 6DoF pose. Use native MyCON Recorder for scientific R4 acquisition."
  };
  if(window.JSZip){
    const zip=new JSZip();
    zip.file("web_recording."+ext,videoBlob);
    zip.file("imu.csv","performance_ms,ax,ay,az,rot_alpha,rot_beta,rot_gamma,interval_ms\n"+motionRows.map(r=>r.join(",")).join("\n"));
    zip.file("gnss.csv","time_ms,lat,lon,alt_m,accuracy_m,heading_deg,speed_mps\n"+geoRows.map(r=>r.join(",")).join("\n"));
    zip.file("session.json",JSON.stringify(manifest,null,2));
    zip.file("README.txt","MYCON Web Fallback Capture\n\nThis package is for field documentation only. It has no metric ARKit/ARCore 6DoF pose and must not be used as a Stage-4 scientific pose source.\n");
    lastWebPackage=await zip.generateAsync({type:"blob",compression:"DEFLATE"});
    lastWebPackageName=safeName(project)+"_MYCON_WEB_FALLBACK.zip";
    $("downloadWebCapture").disabled=false;
  }
  webStartedAt=0;mediaRecorder=null;mediaStream=null;
  $("startWebCapture").disabled=false;$("stopWebCapture").disabled=true;
  $("webCaptureState").textContent="DONE";$("webCaptureState").className="live-pill ok";
  $("webCaptureMessage").textContent="بسته fallback آماده است • "+(videoBlob.size/1024/1024).toFixed(1)+" MB video";
}
$("startWebCapture").addEventListener("click",startWebCapture);
$("stopWebCapture").addEventListener("click",stopWebCapture);
$("downloadWebCapture").addEventListener("click",()=>{if(lastWebPackage)downloadBlob(lastWebPackage,lastWebPackageName);});

function network(){
  const on=navigator.onLine;
  $("onlineBadge").textContent=on?"آنلاین":"آفلاین";
  $("onlineBadge").className="status-dot "+(on?"ok":"warn");
}
addEventListener("online",network);addEventListener("offline",network);
if("serviceWorker"in navigator)addEventListener("load",()=>navigator.serviceWorker.register("./service-worker.js").catch(()=>{}));

drawSaved();drawQaHistory();network();renderMarker().catch(()=>{});
go(location.hash.slice(1)||"homePanel",{hash:false,smooth:false});
