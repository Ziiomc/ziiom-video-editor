const $ = selector => document.querySelector(selector);
const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

const state = {
  media: [],
  clips: [],
  texts: [],
  selectedId: null,
  zoom: 1,
  history: [],
  future: [],
  playhead: 0,
  draggingClipId: null,
  controlEditing: false,
  autosaveTimer: null
};

const videoExt = ['.mp4', '.mov', '.mkv', '.webm', '.avi', '.m4v'];
const audioExt = ['.mp3', '.wav', '.m4a', '.aac', '.ogg', '.flac'];
const imageExt = ['.png', '.jpg', '.jpeg', '.webp', '.bmp'];
const PX_PER_SECOND = 28;
const MIN_TIMELINE_WIDTH = 900;
const AUTOSAVE_KEY = 'ziiom-video-editor-autosave-v2';

function uid() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function fileUrl(filePath) {
  return 'file:///' + filePath.replace(/\\/g, '/').split('/').map((part, index) => index === 0 ? part : encodeURIComponent(part)).join('/');
}

function kind(ext) {
  if (videoExt.includes(ext)) return 'video';
  if (audioExt.includes(ext)) return 'audio';
  if (imageExt.includes(ext)) return 'image';
  return 'other';
}

function fmt(seconds = 0) {
  const sec = Math.max(0, Number(seconds) || 0);
  const hours = Math.floor(sec / 3600);
  const minutes = Math.floor((sec % 3600) / 60);
  const wholeSeconds = Math.floor(sec % 60);
  const centiseconds = Math.floor((sec % 1) * 100);
  const base = `${String(minutes).padStart(2, '0')}:${String(wholeSeconds).padStart(2, '0')}.${String(centiseconds).padStart(2, '0')}`;
  return hours ? `${String(hours).padStart(2, '0')}:${base}` : base;
}

function toast(message) {
  const el = $('#toast');
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove('show'), 2600);
}

function projectSnapshot() {
  return {
    version: 2,
    media: state.media,
    clips: state.clips,
    texts: state.texts,
    zoom: state.zoom
  };
}

function snapshot() {
  state.history.push(JSON.stringify({ clips: state.clips, texts: state.texts }));
  if (state.history.length > 50) state.history.shift();
  state.future = [];
}

function markDirty() {
  $('#projectStatus').textContent = 'Cambios sin guardar';
  scheduleAutosave();
}

function scheduleAutosave() {
  clearTimeout(state.autosaveTimer);
  state.autosaveTimer = setTimeout(() => {
    try {
      localStorage.setItem(AUTOSAVE_KEY, JSON.stringify(projectSnapshot()));
      if ($('#projectStatus').textContent === 'Cambios sin guardar') {
        $('#projectStatus').textContent = 'Autoguardado local';
      }
    } catch {
      // El guardado manual sigue disponible si localStorage no está accesible.
    }
  }, 700);
}

function restoreAutosave() {
  try {
    const raw = localStorage.getItem(AUTOSAVE_KEY);
    if (!raw) return false;
    const data = JSON.parse(raw);
    if (!Array.isArray(data.clips) || (!data.clips.length && !data.media?.length)) return false;
    state.media = data.media || [];
    state.clips = data.clips || [];
    state.texts = data.texts || [];
    state.zoom = clamp(Number(data.zoom) || 1, 0.5, 3);
    state.selectedId = state.clips[0]?.id || null;
    $('#zoomLabel').textContent = `${Math.round(state.zoom * 100)}%`;
    $('#projectStatus').textContent = 'Sesión recuperada automáticamente';
    return true;
  } catch {
    return false;
  }
}

function sourceDuration(clip) {
  const start = Math.max(0, Number(clip.trimStart) || 0);
  const fallbackEnd = clip.kind === 'image' ? 5 : Number(clip.duration) || 5;
  const end = Number(clip.trimEnd) > start ? Number(clip.trimEnd) : fallbackEnd;
  return Math.max(0.05, end - start);
}

function timelineDuration(clip) {
  return sourceDuration(clip) / clamp(Number(clip.speed) || 1, 0.25, 4);
}

function laneForClip(clip) {
  return clip.kind === 'audio' ? 'audio' : 'video';
}

function laneClips(lane) {
  return state.clips.filter(clip => laneForClip(clip) === lane);
}

function laneLayout(lane) {
  let cursor = 0;
  return laneClips(lane).map(clip => {
    const duration = timelineDuration(clip);
    const item = { clip, start: cursor, duration, end: cursor + duration };
    cursor += duration;
    return item;
  });
}

function totalTimelineDuration() {
  const video = laneLayout('video').at(-1)?.end || 0;
  const audio = laneLayout('audio').at(-1)?.end || 0;
  return Math.max(video, audio, 1);
}

function pixelsPerSecond() {
  return PX_PER_SECOND * state.zoom;
}

async function importMedia(filter = 'all') {
  const files = await window.desktop.pickMedia(filter);
  if (!files?.length) return;
  const added = [];
  files.forEach(file => {
    if (!state.media.some(media => media.path === file.path)) {
      const media = { ...file, id: uid(), kind: kind(file.ext) };
      state.media.push(media);
      added.push(media);
    }
  });
  renderMedia();
  if (filter === 'video' && added[0]) addClip(added[0]);
  if (filter === 'audio' && added[0]) addClip(added[0]);
}

function renderMedia() {
  const grid = $('#mediaGrid');
  grid.innerHTML = '';
  state.media.forEach(media => {
    const button = document.createElement('button');
    button.className = 'media-card';
    button.draggable = true;
    button.title = media.name;
    button.innerHTML = `<span class="thumb">${media.kind === 'video' ? '▶' : media.kind === 'audio' ? '♫' : '▧'}</span><span class="media-name"></span>`;
    button.querySelector('.media-name').textContent = media.name;
    button.onclick = () => addClip(media);
    button.ondragstart = event => event.dataTransfer.setData('text/media-id', media.id);
    grid.appendChild(button);
  });
}

function addClip(media, options = {}) {
  snapshot();
  const defaultDuration = media.kind === 'image' ? 5 : 0;
  const clip = {
    id: uid(),
    mediaId: media.id,
    path: media.path,
    name: media.name,
    kind: media.kind,
    trimStart: 0,
    trimEnd: defaultDuration,
    duration: defaultDuration,
    speed: 1,
    volume: 1,
    scale: 1,
    rotation: 0,
    opacity: 1,
    ...options
  };

  const targetLane = laneForClip(clip);
  if (Number.isInteger(options.insertIndex)) {
    const sameLane = laneClips(targetLane);
    sameLane.splice(clamp(options.insertIndex, 0, sameLane.length), 0, clip);
    const otherLane = laneClips(targetLane === 'video' ? 'audio' : 'video');
    state.clips = targetLane === 'video' ? [...sameLane, ...otherLane] : [...otherLane, ...sameLane];
  } else {
    state.clips.push(clip);
  }

  state.selectedId = clip.id;
  renderTimeline();
  selectClip(clip.id);
  markDirty();
  if (media.kind === 'video' || media.kind === 'audio') probeDuration(clip);
}

function probeDuration(clip) {
  const element = document.createElement(clip.kind === 'audio' ? 'audio' : 'video');
  element.preload = 'metadata';
  element.src = fileUrl(clip.path);
  element.onloadedmetadata = () => {
    clip.duration = Number(element.duration) || 0;
    if (!clip.trimEnd || clip.trimEnd > clip.duration) clip.trimEnd = clip.duration;
    renderTimeline();
    if (state.selectedId === clip.id) {
      syncInspector();
      updateTime();
    }
    scheduleAutosave();
    element.removeAttribute('src');
    element.load();
  };
}

function renderTimeline() {
  const videoLayout = laneLayout('video');
  const audioLayout = laneLayout('audio');
  const total = totalTimelineDuration();
  const pps = pixelsPerSecond();
  const contentWidth = Math.max(MIN_TIMELINE_WIDTH, Math.ceil(total * pps + 100));

  renderLane($('#videoTrack'), videoLayout, contentWidth, 'video');
  renderLane($('#audioTrack'), audioLayout, contentWidth, 'audio');
  renderRuler(contentWidth, total);
  updateTimelinePlayhead();
}

function renderLane(lane, layout, contentWidth, laneName) {
  lane.innerHTML = '';
  lane.style.width = `${contentWidth}px`;
  lane.dataset.lane = laneName;

  layout.forEach(item => {
    const { clip, start, duration } = item;
    const element = document.createElement('button');
    element.className = `clip ${clip.kind === 'audio' ? 'audio' : clip.kind === 'image' ? 'image' : ''} ${clip.id === state.selectedId ? 'selected' : ''}`;
    element.dataset.id = clip.id;
    element.style.left = `${start * pixelsPerSecond()}px`;
    element.style.width = `${Math.max(52, duration * pixelsPerSecond())}px`;
    element.draggable = true;

    const title = document.createElement('span');
    title.className = 'clip-title';
    title.textContent = clip.name;
    const meta = document.createElement('small');
    meta.textContent = `${fmt(duration)} · ${clip.speed || 1}x`;
    element.append(title, meta);

    element.onclick = event => {
      if (state.draggingClipId) return;
      const rect = element.getBoundingClientRect();
      const ratio = clamp((event.clientX - rect.left) / Math.max(1, rect.width), 0, 1);
      const sourceTime = (Number(clip.trimStart) || 0) + ratio * sourceDuration(clip);
      state.playhead = start + ratio * duration;
      selectClip(clip.id, { seekTime: sourceTime });
    };

    element.ondragstart = event => {
      state.draggingClipId = clip.id;
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/clip-id', clip.id);
    };
    element.ondragend = () => setTimeout(() => { state.draggingClipId = null; }, 0);
    lane.appendChild(element);
  });

  const playhead = document.createElement('div');
  playhead.className = 'lane-playhead';
  playhead.setAttribute('aria-hidden', 'true');
  lane.appendChild(playhead);
}

function renderRuler(contentWidth, total) {
  const ruler = $('#timeRuler');
  ruler.innerHTML = '';
  ruler.style.width = `${contentWidth}px`;
  const pps = pixelsPerSecond();
  const step = state.zoom >= 2 ? 1 : state.zoom >= 1 ? 5 : 10;
  const max = Math.ceil(total / step) * step + step;
  for (let second = 0; second <= max; second += step) {
    const marker = document.createElement('div');
    marker.className = 'ruler-marker';
    marker.style.left = `${second * pps}px`;
    marker.innerHTML = `<i></i><span>${fmt(second).replace('.00', '')}</span>`;
    ruler.appendChild(marker);
  }
}

function seekLaneAt(lane, clientX) {
  const rect = lane.getBoundingClientRect();
  const time = clamp((clientX - rect.left) / pixelsPerSecond(), 0, totalTimelineDuration());
  state.playhead = time;
  const layout = laneLayout(lane.dataset.lane || 'video');
  const item = layout.find(entry => time >= entry.start && time <= entry.end);
  if (item) {
    const ratio = item.duration ? clamp((time - item.start) / item.duration, 0, 1) : 0;
    const sourceTime = (Number(item.clip.trimStart) || 0) + ratio * sourceDuration(item.clip);
    selectClip(item.clip.id, { seekTime: sourceTime });
  } else {
    updateTimelinePlayhead();
  }
}

function reorderClip(clipId, laneName, clientX) {
  const clip = state.clips.find(item => item.id === clipId);
  if (!clip || laneForClip(clip) !== laneName) return;

  const lane = laneName === 'audio' ? $('#audioTrack') : $('#videoTrack');
  const rect = lane.getBoundingClientRect();
  const targetTime = Math.max(0, (clientX - rect.left) / pixelsPerSecond());
  const without = laneClips(laneName).filter(item => item.id !== clipId);
  let cursor = 0;
  let insertIndex = without.length;
  for (let index = 0; index < without.length; index += 1) {
    const duration = timelineDuration(without[index]);
    if (targetTime < cursor + duration / 2) {
      insertIndex = index;
      break;
    }
    cursor += duration;
  }

  snapshot();
  without.splice(insertIndex, 0, clip);
  const other = laneClips(laneName === 'video' ? 'audio' : 'video');
  state.clips = laneName === 'video' ? [...without, ...other] : [...other, ...without];
  renderTimeline();
  markDirty();
}

['videoTrack', 'audioTrack'].forEach(id => {
  const lane = $(`#${id}`);
  lane.ondragover = event => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  };
  lane.ondrop = event => {
    event.preventDefault();
    const mediaId = event.dataTransfer.getData('text/media-id');
    const clipId = event.dataTransfer.getData('text/clip-id');
    const laneName = lane.dataset.lane || (id === 'audioTrack' ? 'audio' : 'video');

    if (mediaId) {
      const media = state.media.find(item => item.id === mediaId);
      if (!media || laneForClip({ kind: media.kind }) !== laneName) {
        toast(laneName === 'audio' ? 'Arrastra un archivo de audio a esta pista.' : 'Arrastra video o imagen a esta pista.');
        return;
      }
      const targetTime = Math.max(0, (event.clientX - lane.getBoundingClientRect().left) / pixelsPerSecond());
      const layout = laneLayout(laneName);
      let insertIndex = layout.findIndex(item => targetTime < item.start + item.duration / 2);
      if (insertIndex < 0) insertIndex = layout.length;
      addClip(media, { insertIndex });
      return;
    }

    if (clipId) reorderClip(clipId, laneName, event.clientX);
  };
  lane.addEventListener('pointerdown', event => {
    if (event.target === lane || event.target.classList.contains('lane-playhead')) seekLaneAt(lane, event.clientX);
  });
});

$('#timeRuler').addEventListener('pointerdown', event => {
  const rect = $('#timeRuler').getBoundingClientRect();
  state.playhead = clamp((event.clientX - rect.left) / pixelsPerSecond(), 0, totalTimelineDuration());
  const layout = laneLayout('video');
  const item = layout.find(entry => state.playhead >= entry.start && state.playhead <= entry.end);
  if (item) {
    const ratio = clamp((state.playhead - item.start) / item.duration, 0, 1);
    selectClip(item.clip.id, { seekTime: (item.clip.trimStart || 0) + ratio * sourceDuration(item.clip) });
  } else {
    updateTimelinePlayhead();
  }
});

function selected() {
  return state.clips.find(clip => clip.id === state.selectedId);
}

function selectClip(id, options = {}) {
  state.selectedId = id;
  renderTimeline();
  syncInspector();
  loadPreview(options.seekTime);
}

function syncInspector() {
  const clip = selected();
  $('#noSelection').classList.toggle('hidden', Boolean(clip));
  $('#clipInspector').classList.toggle('hidden', !clip);
  if (!clip) return;

  $('#selectedName').textContent = clip.name;
  $('#trimStart').value = Number(clip.trimStart) || 0;
  $('#trimEnd').value = Number(clip.trimEnd) || Number(clip.duration) || (clip.kind === 'image' ? 5 : 0);
  $('#speed').value = String(clip.speed || 1);
  $('#volume').value = clip.volume ?? 1;
  $('#scale').value = clip.scale ?? 1;
  $('#rotation').value = String(clip.rotation || 0);
  $('#opacity').value = clip.opacity ?? 1;
  $('#volumeValue').textContent = `${Math.round((clip.volume ?? 1) * 100)}%`;
  $('#scaleValue').textContent = `${Math.round((clip.scale ?? 1) * 100)}%`;
  $('#opacityValue').textContent = `${Math.round((clip.opacity ?? 1) * 100)}%`;

  const visualDisabled = clip.kind === 'audio';
  ['scale', 'rotation', 'opacity'].forEach(id => { $(`#${id}`).disabled = visualDisabled; });
}

function loadPreview(seekTime) {
  const clip = selected();
  const video = $('#preview');
  const image = $('#imagePreview');
  const empty = $('#emptyPreview');

  video.pause();
  $('#playBtn').textContent = '▶';
  video.style.display = 'none';
  image.style.display = 'none';
  empty.style.display = clip ? 'none' : 'grid';
  if (!clip) {
    updateTime();
    return;
  }

  if (clip.kind === 'image') {
    image.src = fileUrl(clip.path);
    image.style.display = 'block';
    applyVisual(clip, image);
    updateTime();
    return;
  }

  if (clip.kind === 'audio') {
    empty.style.display = 'grid';
    empty.innerHTML = `<strong>Clip de audio seleccionado</strong><span>${clip.name}</span>`;
    updateTime();
    return;
  }

  empty.innerHTML = '<strong>Importa un video para comenzar</strong><span>La línea de tiempo está lista para editar</span>';
  const src = fileUrl(clip.path);
  const desiredTime = Number.isFinite(Number(seekTime)) ? Number(seekTime) : Number(clip.trimStart) || 0;

  const applyLoadedState = () => {
    if (state.selectedId !== clip.id) return;
    if (!clip.duration) clip.duration = Number(video.duration) || 0;
    if (!clip.trimEnd || clip.trimEnd > clip.duration) clip.trimEnd = clip.duration;
    video.currentTime = clamp(desiredTime, clip.trimStart || 0, clip.trimEnd || video.duration || 0);
    video.volume = clamp(Number(clip.volume ?? 1), 0, 1);
    video.playbackRate = clamp(Number(clip.speed) || 1, 0.25, 4);
    applyVisual(clip, video);
    updateTime();
    syncInspector();
    renderTimeline();
  };

  if (video.dataset.path === clip.path && video.readyState >= 1) {
    video.style.display = 'block';
    applyLoadedState();
  } else {
    video.dataset.path = clip.path;
    video.src = src;
    video.style.display = 'block';
    video.onloadedmetadata = applyLoadedState;
  }
}

function applyVisual(clip, element) {
  element.style.transform = `scale(${clip.scale ?? 1}) rotate(${clip.rotation || 0}deg)`;
  element.style.opacity = clip.opacity ?? 1;
}

function sanitizeProperty(clip, key, rawValue) {
  const value = Number(rawValue);
  if (!Number.isFinite(value)) return clip[key];
  if (key === 'trimStart') {
    const max = Math.max(0, (Number(clip.trimEnd) || Number(clip.duration) || 0) - 0.05);
    return clamp(value, 0, max);
  }
  if (key === 'trimEnd') {
    const max = Number(clip.duration) || Math.max(value, Number(clip.trimStart) + 0.05);
    return clamp(value, (Number(clip.trimStart) || 0) + 0.05, max);
  }
  if (key === 'speed') return clamp(value, 0.25, 4);
  if (key === 'volume' || key === 'opacity') return clamp(value, 0, 1);
  if (key === 'scale') return clamp(value, 0.25, 2);
  if (key === 'rotation') return [0, 90, 180, 270].includes(value) ? value : 0;
  return value;
}

function updateFromInspector(key, value) {
  const clip = selected();
  if (!clip) return;
  clip[key] = sanitizeProperty(clip, key, value);

  const video = $('#preview');
  if (key === 'scale' || key === 'rotation' || key === 'opacity') {
    const visual = clip.kind === 'image' ? $('#imagePreview') : video;
    applyVisual(clip, visual);
  }
  if (key === 'volume' && clip.kind === 'video') video.volume = clip.volume;
  if (key === 'speed' && clip.kind === 'video') video.playbackRate = clip.speed;
  if ((key === 'trimStart' || key === 'trimEnd') && clip.kind === 'video') {
    video.currentTime = clamp(video.currentTime, clip.trimStart || 0, clip.trimEnd || video.duration || 0);
  }

  if (['trimStart', 'trimEnd', 'speed'].includes(key)) renderTimeline();
  syncInspector();
  updateTime();
  markDirty();
}

function bindInspectorControl(key) {
  const control = $(`#${key}`);
  const begin = () => {
    if (!state.controlEditing) {
      snapshot();
      state.controlEditing = true;
    }
  };
  control.addEventListener('pointerdown', begin);
  control.addEventListener('focus', begin);
  control.addEventListener('input', event => updateFromInspector(key, event.target.value));
  control.addEventListener('change', () => { state.controlEditing = false; });
  control.addEventListener('blur', () => { state.controlEditing = false; });
}

['trimStart', 'trimEnd', 'speed', 'volume', 'scale', 'rotation', 'opacity'].forEach(bindInspectorControl);

function togglePlayback() {
  const clip = selected();
  const video = $('#preview');
  if (!clip || clip.kind !== 'video') return;
  if (video.paused) {
    if (video.currentTime < (clip.trimStart || 0) || video.currentTime >= (clip.trimEnd || video.duration)) {
      video.currentTime = clip.trimStart || 0;
    }
    video.play();
    $('#playBtn').textContent = '❚❚';
  } else {
    video.pause();
    $('#playBtn').textContent = '▶';
  }
}

function stepPreview(delta) {
  const clip = selected();
  const video = $('#preview');
  if (!clip || clip.kind !== 'video') return;
  video.currentTime = clamp(video.currentTime + delta, clip.trimStart || 0, clip.trimEnd || video.duration || 0);
  updateTime();
}

$('#playBtn').onclick = togglePlayback;
$('#previewSpeed').onchange = event => { $('#preview').playbackRate = Number(event.target.value); };
$('#prevBtn').onclick = () => stepPreview(-1);
$('#nextBtn').onclick = () => stepPreview(1);
$('#preview').ontimeupdate = () => {
  const clip = selected();
  const video = $('#preview');
  if (!clip || clip.kind !== 'video') return;
  if (clip.trimEnd && video.currentTime >= clip.trimEnd) {
    video.pause();
    video.currentTime = clip.trimEnd;
    $('#playBtn').textContent = '▶';
  }
  const layoutItem = laneLayout('video').find(item => item.clip.id === clip.id);
  if (layoutItem) {
    const ratio = clamp((video.currentTime - (clip.trimStart || 0)) / sourceDuration(clip), 0, 1);
    state.playhead = layoutItem.start + ratio * layoutItem.duration;
  }
  updateTime();
};

function updateTime() {
  const clip = selected();
  const video = $('#preview');
  if (!clip) {
    $('#timeLabel').textContent = '00:00.00 / 00:00.00';
    $('#scrubber').value = 0;
    updateTimelinePlayhead();
    return;
  }

  const start = Number(clip.trimStart) || 0;
  const end = Number(clip.trimEnd) || Number(clip.duration) || (clip.kind === 'image' ? 5 : 0);
  const current = clip.kind === 'video' ? clamp(video.currentTime || start, start, end) : start;
  $('#timeLabel').textContent = `${fmt(current)} / ${fmt(end)}`;
  $('#scrubber').value = end > start ? Math.round(((current - start) / (end - start)) * 1000) : 0;
  updateTimelinePlayhead();
}

$('#scrubber').oninput = event => {
  const clip = selected();
  const video = $('#preview');
  if (!clip || clip.kind !== 'video') return;
  const start = Number(clip.trimStart) || 0;
  const end = Number(clip.trimEnd) || video.duration || start;
  const ratio = Number(event.target.value) / 1000;
  video.currentTime = start + ratio * Math.max(0, end - start);
  const layoutItem = laneLayout('video').find(item => item.clip.id === clip.id);
  if (layoutItem) state.playhead = layoutItem.start + ratio * layoutItem.duration;
  updateTime();
};

function updateTimelinePlayhead() {
  const left = `${Math.max(0, state.playhead) * pixelsPerSecond()}px`;
  document.querySelectorAll('.lane-playhead').forEach(playhead => { playhead.style.left = left; });
  $('#timelineTime').textContent = fmt(state.playhead);
}

function splitSelected() {
  const clip = selected();
  const video = $('#preview');
  if (!clip || clip.kind !== 'video') return;
  const time = video.currentTime;
  if (time <= (clip.trimStart || 0) + 0.05 || time >= (clip.trimEnd || clip.duration) - 0.05) {
    toast('Mueve el cursor dentro del clip para dividir.');
    return;
  }
  snapshot();
  const copy = { ...clip, id: uid(), trimStart: time };
  clip.trimEnd = time;
  state.clips.splice(state.clips.indexOf(clip) + 1, 0, copy);
  state.selectedId = copy.id;
  renderTimeline();
  syncInspector();
  loadPreview(copy.trimStart);
  markDirty();
  toast('Clip dividido');
}

function duplicateSelected() {
  const clip = selected();
  if (!clip) return;
  snapshot();
  const copy = { ...clip, id: uid(), name: `${clip.name} copia` };
  state.clips.splice(state.clips.indexOf(clip) + 1, 0, copy);
  state.selectedId = copy.id;
  renderTimeline();
  syncInspector();
  loadPreview(copy.trimStart || 0);
  markDirty();
}

function deleteSelected() {
  const clip = selected();
  if (!clip) return;
  snapshot();
  state.clips = state.clips.filter(item => item.id !== clip.id);
  state.selectedId = laneClips('video')[0]?.id || state.clips[0]?.id || null;
  state.playhead = 0;
  renderTimeline();
  syncInspector();
  loadPreview();
  markDirty();
}

$('#splitBtn').onclick = splitSelected;
$('#duplicateBtn').onclick = duplicateSelected;
$('#deleteBtn').onclick = deleteSelected;

$('#resetVisualBtn').onclick = () => {
  const clip = selected();
  if (!clip) return toast('Selecciona un clip primero.');
  snapshot();
  clip.scale = 1;
  clip.rotation = 0;
  clip.opacity = 1;
  syncInspector();
  if (clip.kind !== 'audio') applyVisual(clip, clip.kind === 'image' ? $('#imagePreview') : $('#preview'));
  markDirty();
};

$('#muteBtn').onclick = () => {
  const clip = selected();
  if (!clip) return toast('Selecciona un clip primero.');
  snapshot();
  clip.volume = clip.volume > 0 ? 0 : 1;
  if (clip.kind === 'video') $('#preview').volume = clip.volume;
  syncInspector();
  markDirty();
};

$('#addTextBtn').onclick = () => {
  const text = $('#textInput').value.trim();
  if (!text) return;
  snapshot();
  state.texts.push({ id: uid(), text, size: Number($('#textSize').value), color: $('#textColor').value, x: 50, y: 18 });
  renderTexts();
  markDirty();
};

function renderTexts() {
  const layer = $('#textLayer');
  layer.innerHTML = '';
  state.texts.forEach(text => {
    const element = document.createElement('div');
    element.className = 'text-overlay';
    element.textContent = text.text;
    element.style.fontSize = `${text.size}px`;
    element.style.color = text.color;
    element.style.left = `${text.x}%`;
    element.style.top = `${text.y}%`;
    let dragging = false;
    let snapshotted = false;
    element.onpointerdown = event => {
      dragging = true;
      if (!snapshotted) {
        snapshot();
        snapshotted = true;
      }
      element.setPointerCapture(event.pointerId);
    };
    element.onpointermove = event => {
      if (!dragging) return;
      const rect = $('#canvas').getBoundingClientRect();
      text.x = clamp(((event.clientX - rect.left) / rect.width) * 100, 0, 100);
      text.y = clamp(((event.clientY - rect.top) / rect.height) * 100, 0, 100);
      element.style.left = `${text.x}%`;
      element.style.top = `${text.y}%`;
      markDirty();
    };
    element.onpointerup = () => { dragging = false; snapshotted = false; };
    layer.appendChild(element);
  });
}

async function saveProject() {
  const path = await window.desktop.saveProject(projectSnapshot());
  if (path) {
    $('#projectStatus').textContent = 'Guardado';
    scheduleAutosave();
    toast('Proyecto guardado');
  }
}

async function openProject() {
  const response = await window.desktop.openProject();
  if (!response) return;
  const data = response.data || {};
  state.media = data.media || [];
  state.clips = data.clips || [];
  state.texts = data.texts || [];
  state.zoom = clamp(Number(data.zoom) || 1, 0.5, 3);
  state.selectedId = state.clips[0]?.id || null;
  state.playhead = 0;
  state.history = [];
  state.future = [];
  $('#zoomLabel').textContent = `${Math.round(state.zoom * 100)}%`;
  renderMedia();
  renderTimeline();
  renderTexts();
  syncInspector();
  loadPreview();
  $('#projectStatus').textContent = 'Proyecto abierto';
  scheduleAutosave();
  toast('Proyecto cargado');
}

async function exportProject() {
  const videoClips = laneClips('video');
  if (!videoClips.length) return toast('Agrega al menos un video o una imagen a V1.');
  const button = $('#exportBtn');
  const old = button.textContent;
  button.disabled = true;
  button.textContent = 'Exportando…';
  try {
    const path = await window.desktop.exportTimeline({ clips: state.clips, texts: state.texts });
    if (path) toast('Proyecto exportado correctamente');
  } catch (error) {
    toast(`Error al exportar: ${String(error.message || error).slice(0, 120)}`);
  } finally {
    button.disabled = false;
    button.textContent = old;
  }
}

$('#importBtn').onclick = () => importMedia('all');
$('#importVideoBtn').onclick = () => importMedia('video');
$('#importAudioBtn').onclick = () => importMedia('audio');
$('#saveProjectBtn').onclick = saveProject;
$('#openProjectBtn').onclick = openProject;
$('#exportBtn').onclick = exportProject;

function restoreHistory(payload) {
  state.clips = payload.clips || [];
  state.texts = payload.texts || [];
  if (!state.clips.some(clip => clip.id === state.selectedId)) state.selectedId = state.clips[0]?.id || null;
  state.playhead = 0;
  renderTimeline();
  renderTexts();
  syncInspector();
  loadPreview();
  markDirty();
}

function undo() {
  if (!state.history.length) return;
  state.future.push(JSON.stringify({ clips: state.clips, texts: state.texts }));
  restoreHistory(JSON.parse(state.history.pop()));
}

function redo() {
  if (!state.future.length) return;
  state.history.push(JSON.stringify({ clips: state.clips, texts: state.texts }));
  restoreHistory(JSON.parse(state.future.pop()));
}

$('#undoBtn').onclick = undo;
$('#redoBtn').onclick = redo;
$('#zoomIn').onclick = () => {
  state.zoom = Math.min(3, state.zoom + 0.25);
  $('#zoomLabel').textContent = `${Math.round(state.zoom * 100)}%`;
  renderTimeline();
};
$('#zoomOut').onclick = () => {
  state.zoom = Math.max(0.5, state.zoom - 0.25);
  $('#zoomLabel').textContent = `${Math.round(state.zoom * 100)}%`;
  renderTimeline();
};

document.querySelectorAll('.tab').forEach(tab => {
  tab.onclick = () => {
    document.querySelectorAll('.tab').forEach(item => item.classList.remove('active'));
    document.querySelectorAll('.panel').forEach(item => item.classList.remove('active-panel'));
    tab.classList.add('active');
    $(`#${tab.dataset.tab}Panel`).classList.add('active-panel');
  };
});

document.addEventListener('keydown', event => {
  const tag = document.activeElement?.tagName;
  const editingText = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';

  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    saveProject();
    return;
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {
    event.preventDefault();
    undo();
    return;
  }
  if ((event.ctrlKey || event.metaKey) && (event.key.toLowerCase() === 'y' || (event.shiftKey && event.key.toLowerCase() === 'z'))) {
    event.preventDefault();
    redo();
    return;
  }
  if (editingText) return;
  if (event.code === 'Space') {
    event.preventDefault();
    togglePlayback();
  } else if (event.key === 'ArrowLeft') {
    event.preventDefault();
    stepPreview(-1);
  } else if (event.key === 'ArrowRight') {
    event.preventDefault();
    stepPreview(1);
  } else if (event.key.toLowerCase() === 's') {
    event.preventDefault();
    splitSelected();
  } else if (event.key === 'Delete' || event.key === 'Backspace') {
    event.preventDefault();
    deleteSelected();
  }
});

if (window.desktop?.onExportLog) {
  window.desktop.onExportLog(message => {
    if (String(message).includes('time=')) $('#projectStatus').textContent = 'Exportando proyecto…';
  });
}

restoreAutosave();
renderMedia();
renderTimeline();
renderTexts();
syncInspector();
loadPreview();
