const $ = s => document.querySelector(s);
const state = { media: [], clips: [], texts: [], selectedId: null, zoom: 1, history: [], future: [] };
const videoExt = ['.mp4','.mov','.mkv','.webm','.avi'];
const audioExt = ['.mp3','.wav','.m4a','.aac','.ogg'];
const imageExt = ['.png','.jpg','.jpeg','.webp'];

function uid(){ return `${Date.now()}-${Math.random().toString(36).slice(2,8)}`; }
function fileUrl(p){ return 'file:///' + p.replace(/\\/g,'/').split('/').map((x,i)=>i===0?x:encodeURIComponent(x)).join('/'); }
function kind(ext){ if(videoExt.includes(ext)) return 'video'; if(audioExt.includes(ext)) return 'audio'; if(imageExt.includes(ext)) return 'image'; return 'other'; }
function fmt(sec=0){ sec=Math.max(0,Number(sec)||0); const m=Math.floor(sec/60); const s=Math.floor(sec%60); const cs=Math.floor((sec%1)*100); return `${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}.${String(cs).padStart(2,'0')}`; }
function toast(msg){ const el=$('#toast'); el.textContent=msg; el.classList.add('show'); setTimeout(()=>el.classList.remove('show'),2400); }
function snapshot(){ state.history.push(JSON.stringify({clips:state.clips,texts:state.texts})); if(state.history.length>40) state.history.shift(); state.future=[]; }
function markDirty(){ $('#projectStatus').textContent='Cambios sin guardar'; }

async function importMedia(){
  const files = await window.desktop.pickMedia();
  files.forEach(f => {
    if(!state.media.some(m=>m.path===f.path)) state.media.push({...f,id:uid(),kind:kind(f.ext)});
  });
  renderMedia();
}

function renderMedia(){
  const grid=$('#mediaGrid'); grid.innerHTML='';
  state.media.forEach(m=>{
    const b=document.createElement('button'); b.className='media-card'; b.draggable=true;
    b.innerHTML=`<span class="thumb">${m.kind==='video'?'🎬':m.kind==='audio'?'♫':'▧'}</span><span class="media-name">${m.name}</span>`;
    b.onclick=()=>addClip(m);
    b.ondragstart=e=>e.dataTransfer.setData('text/media-id',m.id);
    grid.appendChild(b);
  });
}

function addClip(media, opts={}){
  snapshot();
  const clip={id:uid(),mediaId:media.id,path:media.path,name:media.name,kind:media.kind,trimStart:0,trimEnd:0,duration:0,speed:1,volume:1,scale:1,rotation:0,opacity:1,...opts};
  state.clips.push(clip); state.selectedId=clip.id; renderTimeline(); selectClip(clip.id); markDirty();
  if(media.kind==='video'||media.kind==='audio') probeDuration(clip);
}

function probeDuration(clip){
  const el=document.createElement(clip.kind==='audio'?'audio':'video');
  el.preload='metadata'; el.src=fileUrl(clip.path);
  el.onloadedmetadata=()=>{ clip.duration=Number(el.duration)||0; if(!clip.trimEnd) clip.trimEnd=clip.duration; renderTimeline(); if(state.selectedId===clip.id) syncInspector(); };
}

function renderTimeline(){
  const v=$('#videoTrack'), a=$('#audioTrack'); v.innerHTML=''; a.innerHTML='';
  state.clips.forEach(c=>{
    const el=document.createElement('button'); el.className=`clip ${c.kind==='audio'?'audio':''} ${c.id===state.selectedId?'selected':''}`;
    const dur=Math.max(1,(c.trimEnd||c.duration||5)-(c.trimStart||0));
    el.style.width=`${Math.max(120,Math.min(520,dur*28*state.zoom))}px`;
    el.draggable=true; el.dataset.id=c.id; el.innerHTML=`${c.name}<small>${fmt(dur)} · ${c.speed}x</small>`;
    el.onclick=()=>selectClip(c.id);
    el.ondragstart=e=>e.dataTransfer.setData('text/clip-id',c.id);
    (c.kind==='audio'?a:v).appendChild(el);
  });
}

['videoTrack','audioTrack'].forEach(id=>{
  const lane=$('#'+id);
  lane.ondragover=e=>e.preventDefault();
  lane.ondrop=e=>{
    e.preventDefault(); const mid=e.dataTransfer.getData('text/media-id'); const cid=e.dataTransfer.getData('text/clip-id');
    if(mid){ const media=state.media.find(m=>m.id===mid); if(media) addClip(media); return; }
    if(cid){ snapshot(); const i=state.clips.findIndex(c=>c.id===cid); if(i>=0){ const [c]=state.clips.splice(i,1); state.clips.push(c); renderTimeline(); markDirty(); } }
  };
});

function selectClip(id){ state.selectedId=id; renderTimeline(); syncInspector(); loadPreview(); }
function selected(){ return state.clips.find(c=>c.id===state.selectedId); }

function syncInspector(){
  const c=selected(); $('#noSelection').classList.toggle('hidden',!!c); $('#clipInspector').classList.toggle('hidden',!c); if(!c)return;
  $('#selectedName').textContent=c.name; $('#trimStart').value=c.trimStart||0; $('#trimEnd').value=c.trimEnd||c.duration||0; $('#speed').value=String(c.speed||1); $('#volume').value=c.volume??1; $('#scale').value=c.scale??1; $('#rotation').value=String(c.rotation||0); $('#opacity').value=c.opacity??1;
}

function loadPreview(){
  const c=selected(), v=$('#preview'), img=$('#imagePreview'), empty=$('#emptyPreview');
  v.pause(); v.style.display='none'; img.style.display='none'; empty.style.display=c?'none':'grid'; if(!c)return;
  if(c.kind==='image'){ img.src=fileUrl(c.path); img.style.display='block'; applyVisual(c,img); return; }
  if(c.kind==='video'){ v.src=fileUrl(c.path); v.style.display='block'; v.volume=Math.min(1,c.volume??1); v.playbackRate=c.speed||1; v.onloadedmetadata=()=>{ if(!c.duration)c.duration=v.duration; if(!c.trimEnd)c.trimEnd=v.duration; v.currentTime=Math.min(c.trimStart||0,v.duration||0); updateTime(); syncInspector(); renderTimeline(); }; applyVisual(c,v); }
}
function applyVisual(c,el){ el.style.transform=`scale(${c.scale||1}) rotate(${c.rotation||0}deg)`; el.style.opacity=c.opacity??1; }

function updateFromInspector(key,value){ const c=selected(); if(!c)return; snapshot(); c[key]=Number(value); if(key==='trimStart'&&c.trimEnd<c.trimStart)c.trimEnd=c.trimStart; renderTimeline(); syncInspector(); loadPreview(); markDirty(); }
['trimStart','trimEnd','speed','volume','scale','rotation','opacity'].forEach(key=>{$('#'+key).addEventListener('change',e=>updateFromInspector(key,e.target.value));});

$('#playBtn').onclick=()=>{ const c=selected(),v=$('#preview'); if(!c||c.kind!=='video')return; if(v.paused){ if(v.currentTime<(c.trimStart||0)||v.currentTime>=(c.trimEnd||v.duration))v.currentTime=c.trimStart||0; v.play(); $('#playBtn').textContent='❚❚'; } else {v.pause(); $('#playBtn').textContent='▶';} };
$('#previewSpeed').onchange=e=>{ $('#preview').playbackRate=Number(e.target.value); };
$('#prevBtn').onclick=()=>{ const c=selected(),v=$('#preview'); if(c&&c.kind==='video')v.currentTime=Math.max(c.trimStart||0,v.currentTime-1); };
$('#nextBtn').onclick=()=>{ const c=selected(),v=$('#preview'); if(c&&c.kind==='video')v.currentTime=Math.min(c.trimEnd||v.duration,v.currentTime+1); };
$('#preview').ontimeupdate=()=>{ const c=selected(),v=$('#preview'); if(!c)return; if(c.trimEnd&&v.currentTime>=c.trimEnd){v.pause();v.currentTime=c.trimEnd;$('#playBtn').textContent='▶';} updateTime(); };
function updateTime(){ const v=$('#preview'),c=selected(); const end=c?.trimEnd||v.duration||0; $('#timeLabel').textContent=`${fmt(v.currentTime)} / ${fmt(end)}`; $('#scrubber').value=end?Math.min(1000,(v.currentTime/end)*1000):0; }
$('#scrubber').oninput=e=>{ const c=selected(),v=$('#preview'); if(c?.kind==='video'){ const end=c.trimEnd||v.duration||0; v.currentTime=(Number(e.target.value)/1000)*end; } };

$('#splitBtn').onclick=()=>{ const c=selected(),v=$('#preview'); if(!c||c.kind!=='video')return; const t=v.currentTime; if(t<=c.trimStart+.05||t>=c.trimEnd-.05)return toast('Mueve el cursor dentro del clip para dividir.'); snapshot(); const copy={...c,id:uid(),trimStart:t}; c.trimEnd=t; state.clips.splice(state.clips.indexOf(c)+1,0,copy); state.selectedId=copy.id; renderTimeline(); syncInspector(); markDirty(); toast('Clip dividido'); };
$('#duplicateBtn').onclick=()=>{ const c=selected(); if(!c)return; snapshot(); const copy={...c,id:uid(),name:`${c.name} copia`}; state.clips.splice(state.clips.indexOf(c)+1,0,copy); state.selectedId=copy.id; renderTimeline(); syncInspector(); markDirty(); };
$('#deleteBtn').onclick=()=>{ const c=selected(); if(!c)return; snapshot(); state.clips=state.clips.filter(x=>x.id!==c.id); state.selectedId=state.clips[0]?.id||null; renderTimeline(); syncInspector(); loadPreview(); markDirty(); };

$('#addTextBtn').onclick=()=>{ const text=$('#textInput').value.trim(); if(!text)return; snapshot(); state.texts.push({id:uid(),text,size:Number($('#textSize').value),color:$('#textColor').value,x:50,y:18}); renderTexts(); markDirty(); };
function renderTexts(){ const layer=$('#textLayer'); layer.innerHTML=''; state.texts.forEach(t=>{ const el=document.createElement('div'); el.className='text-overlay'; el.textContent=t.text; el.style.fontSize=t.size+'px'; el.style.color=t.color; el.style.left=t.x+'%'; el.style.top=t.y+'%'; let drag=false; el.onpointerdown=e=>{drag=true;el.setPointerCapture(e.pointerId)}; el.onpointermove=e=>{if(!drag)return;const r=$('#canvas').getBoundingClientRect();t.x=Math.max(0,Math.min(100,(e.clientX-r.left)/r.width*100));t.y=Math.max(0,Math.min(100,(e.clientY-r.top)/r.height*100));el.style.left=t.x+'%';el.style.top=t.y+'%';markDirty()}; el.onpointerup=()=>drag=false; layer.appendChild(el); }); }

$('#importBtn').onclick=importMedia;
$('#saveProjectBtn').onclick=async()=>{ const p=await window.desktop.saveProject({version:1,media:state.media,clips:state.clips,texts:state.texts}); if(p){$('#projectStatus').textContent='Guardado';toast('Proyecto guardado');} };
$('#openProjectBtn').onclick=async()=>{ const res=await window.desktop.openProject(); if(!res)return; const d=res.data||{}; state.media=d.media||[];state.clips=d.clips||[];state.texts=d.texts||[];state.selectedId=state.clips[0]?.id||null;renderMedia();renderTimeline();renderTexts();syncInspector();loadPreview();$('#projectStatus').textContent='Proyecto abierto';toast('Proyecto cargado'); };
$('#exportBtn').onclick=async()=>{ const c=selected(); if(!c||c.kind!=='video')return toast('Selecciona un clip de video para exportar.'); const b=$('#exportBtn'); const old=b.textContent; b.disabled=true;b.textContent='Exportando…'; try{const p=await window.desktop.exportClip(c);if(p)toast('Video exportado correctamente');}catch(e){toast('Error al exportar: '+e.message.slice(0,100));}finally{b.disabled=false;b.textContent=old;} };

$('#undoBtn').onclick=()=>{ if(!state.history.length)return; state.future.push(JSON.stringify({clips:state.clips,texts:state.texts})); const prev=JSON.parse(state.history.pop());state.clips=prev.clips;state.texts=prev.texts;state.selectedId=state.clips[0]?.id||null;renderTimeline();renderTexts();syncInspector();loadPreview();markDirty(); };
$('#redoBtn').onclick=()=>{ if(!state.future.length)return; state.history.push(JSON.stringify({clips:state.clips,texts:state.texts})); const next=JSON.parse(state.future.pop());state.clips=next.clips;state.texts=next.texts;state.selectedId=state.clips[0]?.id||null;renderTimeline();renderTexts();syncInspector();loadPreview();markDirty(); };
$('#zoomIn').onclick=()=>{state.zoom=Math.min(3,state.zoom+.25);$('#zoomLabel').textContent=Math.round(state.zoom*100)+'%';renderTimeline();};
$('#zoomOut').onclick=()=>{state.zoom=Math.max(.5,state.zoom-.25);$('#zoomLabel').textContent=Math.round(state.zoom*100)+'%';renderTimeline();};

document.querySelectorAll('.tab').forEach(tab=>tab.onclick=()=>{document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));document.querySelectorAll('.panel').forEach(x=>x.classList.remove('active-panel'));tab.classList.add('active');$('#'+tab.dataset.tab+'Panel').classList.add('active-panel');});

renderTimeline(); renderTexts();
