const CACHE="mycon-web-v3-ui";
const CORE=["./","./index.html","./app.css","./app.js","./manifest.webmanifest","./icon.svg"];
const OPTIONAL=[
  "https://cdn.jsdelivr.net/npm/qrcode@1.5.4/build/qrcode.js",
  "https://cdn.jsdelivr.net/npm/jszip@3.10.2/dist/jszip.min.js",
  "https://ajax.googleapis.com/ajax/libs/model-viewer/4.3.1/model-viewer.min.js",
  "https://cdn.jsdelivr.net/gh/DediData/Yekan-Font@d25d796fd4862d5e9a6426669a603807dfa3805f/Yekan.woff2",
  "https://cdn.jsdelivr.net/gh/DediData/Yekan-Font@d25d796fd4862d5e9a6426669a603807dfa3805f/Yekan.woff"
];

self.addEventListener("install",event=>{
  event.waitUntil((async()=>{
    const cache=await caches.open(CACHE);
    await cache.addAll(CORE);
    await Promise.allSettled(OPTIONAL.map(async url=>{
      const response=await fetch(url,{mode:"cors"});
      if(response.ok)await cache.put(url,response);
    }));
    await self.skipWaiting();
  })());
});

self.addEventListener("activate",event=>{
  event.waitUntil((async()=>{
    const keys=await caches.keys();
    await Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k)));
    await self.clients.claim();
  })());
});

self.addEventListener("fetch",event=>{
  if(event.request.method!=="GET")return;
  event.respondWith((async()=>{
    const cached=await caches.match(event.request);
    if(cached)return cached;
    try{
      const response=await fetch(event.request);
      if(response.ok){
        const cache=await caches.open(CACHE);
        cache.put(event.request,response.clone()).catch(()=>{});
      }
      return response;
    }catch{
      if(event.request.mode==="navigate")return caches.match("./index.html");
      throw new Error("offline");
    }
  })());
});
