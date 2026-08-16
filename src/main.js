const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { spawn } = require('child_process');

function createWindow() {
  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 980,
    minHeight: 640,
    backgroundColor: '#0b0f17',
    titleBarStyle: 'hiddenInset',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true
    }
  });

  win.loadFile(path.join(__dirname, 'index.html'));
}

app.whenReady().then(() => {
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

ipcMain.handle('pick-media', async (_event, filter = 'all') => {
  const filterMap = {
    video: [{ name: 'Video', extensions: ['mp4', 'mov', 'mkv', 'webm', 'avi', 'm4v'] }],
    audio: [{ name: 'Audio', extensions: ['mp3', 'wav', 'm4a', 'aac', 'ogg', 'flac'] }],
    all: [{ name: 'Multimedia', extensions: ['mp4', 'mov', 'mkv', 'webm', 'avi', 'm4v', 'mp3', 'wav', 'm4a', 'aac', 'ogg', 'flac', 'png', 'jpg', 'jpeg', 'webp', 'bmp'] }]
  };
  const result = await dialog.showOpenDialog({
    title: 'Importar archivos',
    properties: ['openFile', 'multiSelections'],
    filters: filterMap[filter] || filterMap.all
  });
  if (result.canceled) return [];
  return result.filePaths.map(filePath => ({
    path: filePath,
    name: path.basename(filePath),
    ext: path.extname(filePath).toLowerCase()
  }));
});

ipcMain.handle('save-project', async (_event, data) => {
  const result = await dialog.showSaveDialog({
    title: 'Guardar proyecto',
    defaultPath: 'proyecto.zve.json',
    filters: [{ name: 'Proyecto Ziiom Video Editor', extensions: ['json'] }]
  });
  if (result.canceled || !result.filePath) return null;
  fs.writeFileSync(result.filePath, JSON.stringify(data, null, 2), 'utf8');
  return result.filePath;
});

ipcMain.handle('open-project', async () => {
  const result = await dialog.showOpenDialog({
    title: 'Abrir proyecto',
    properties: ['openFile'],
    filters: [{ name: 'Proyecto Ziiom Video Editor', extensions: ['json'] }]
  });
  if (result.canceled || !result.filePaths[0]) return null;
  const filePath = result.filePaths[0];
  return { filePath, data: JSON.parse(fs.readFileSync(filePath, 'utf8')) };
});

function getFfmpegPath() {
  if (app.isPackaged && process.platform === 'win32') {
    const bundled = path.join(process.resourcesPath, 'ffmpeg.exe');
    if (fs.existsSync(bundled)) return bundled;
  }
  try {
    return require('ffmpeg-static');
  } catch {
    return 'ffmpeg';
  }
}

function runFfmpeg(args, event, options = {}) {
  return new Promise((resolve, reject) => {
    const proc = spawn(getFfmpegPath(), args, { windowsHide: true, ...options });
    let stderr = '';
    proc.stderr.on('data', chunk => {
      const text = chunk.toString();
      stderr += text;
      event?.sender?.send('export-log', text);
    });
    proc.on('error', reject);
    proc.on('close', code => {
      if (code === 0) resolve(stderr);
      else reject(new Error(stderr.split('\n').slice(-14).join('\n') || `FFmpeg terminó con código ${code}`));
    });
  });
}

async function sourceHasAudio(filePath, event) {
  try {
    const output = await runFfmpeg(['-hide_banner', '-t', '0.05', '-i', filePath, '-f', 'null', '-'], event);
    return /Stream #\d+:\d+(?:\([^)]*\))?: Audio:/i.test(output);
  } catch (error) {
    return /Stream #\d+:\d+(?:\([^)]*\))?: Audio:/i.test(String(error.message));
  }
}

function clipSourceDuration(clip) {
  const start = Math.max(0, Number(clip.trimStart) || 0);
  const fallbackEnd = clip.kind === 'image' ? 5 : Number(clip.duration) || 5;
  const end = Number(clip.trimEnd) > start ? Number(clip.trimEnd) : fallbackEnd;
  return Math.max(0.05, end - start);
}

function clipTimelineDuration(clip) {
  const speed = Math.max(0.25, Math.min(4, Number(clip.speed) || 1));
  return clipSourceDuration(clip) / speed;
}

function atempoFilters(speed) {
  const filters = [];
  let remaining = Math.max(0.25, Math.min(4, Number(speed) || 1));
  while (remaining > 2) {
    filters.push('atempo=2');
    remaining /= 2;
  }
  while (remaining < 0.5) {
    filters.push('atempo=0.5');
    remaining /= 0.5;
  }
  if (Math.abs(remaining - 1) > 0.001) filters.push(`atempo=${remaining.toFixed(6)}`);
  return filters;
}

function videoFilters(clip) {
  const filters = [];
  const rotation = Number(clip.rotation) || 0;
  const scale = Math.max(0.25, Math.min(2, Number(clip.scale) || 1));
  const opacity = Math.max(0, Math.min(1, Number(clip.opacity) ?? 1));
  const speed = Math.max(0.25, Math.min(4, Number(clip.speed) || 1));

  if (rotation === 90) filters.push('transpose=1');
  if (rotation === 180) filters.push('transpose=1', 'transpose=1');
  if (rotation === 270) filters.push('transpose=2');

  filters.push('scale=1280:720:force_original_aspect_ratio=decrease');
  filters.push('pad=1280:720:(ow-iw)/2:(oh-ih)/2:black');

  if (scale > 1.001) {
    filters.push(`scale=iw*${scale.toFixed(4)}:ih*${scale.toFixed(4)}`);
    filters.push('crop=1280:720');
  } else if (scale < 0.999) {
    filters.push(`scale=iw*${scale.toFixed(4)}:ih*${scale.toFixed(4)}`);
    filters.push('pad=1280:720:(ow-iw)/2:(oh-ih)/2:black');
  }

  if (opacity < 0.999) {
    filters.push(`colorchannelmixer=rr=${opacity.toFixed(4)}:gg=${opacity.toFixed(4)}:bb=${opacity.toFixed(4)}`);
  }
  if (Math.abs(speed - 1) > 0.001) filters.push(`setpts=${(1 / speed).toFixed(6)}*PTS`);

  filters.push('fps=30', 'format=yuv420p');
  return filters.join(',');
}

function audioFilters(clip) {
  const volume = Math.max(0, Math.min(1, Number(clip.volume) ?? 1));
  const filters = atempoFilters(clip.speed);
  if (Math.abs(volume - 1) > 0.001) filters.push(`volume=${volume.toFixed(4)}`);
  filters.push('aresample=48000');
  return filters.join(',');
}

function concatLine(filePath) {
  const normalized = filePath.replace(/\\/g, '/').replace(/'/g, "'\\''");
  return `file '${normalized}'`;
}

async function renderVideoSegment(clip, outputPath, event) {
  const sourceDuration = clipSourceDuration(clip);
  const outputDuration = clipTimelineDuration(clip);
  const vf = videoFilters(clip);
  const args = ['-y'];

  if (clip.kind === 'image') {
    args.push('-loop', '1', '-t', String(outputDuration), '-i', clip.path);
    args.push('-f', 'lavfi', '-t', String(outputDuration), '-i', 'anullsrc=channel_layout=stereo:sample_rate=48000');
    args.push('-map', '0:v:0', '-map', '1:a:0', '-vf', vf);
  } else {
    const start = Math.max(0, Number(clip.trimStart) || 0);
    const hasAudio = await sourceHasAudio(clip.path, event);
    args.push('-ss', String(start), '-t', String(sourceDuration), '-i', clip.path);
    if (hasAudio) {
      args.push('-map', '0:v:0', '-map', '0:a:0', '-vf', vf);
      const af = audioFilters(clip);
      if (af) args.push('-af', af);
    } else {
      args.push('-f', 'lavfi', '-t', String(outputDuration), '-i', 'anullsrc=channel_layout=stereo:sample_rate=48000');
      args.push('-map', '0:v:0', '-map', '1:a:0', '-vf', vf);
    }
  }

  args.push('-r', '30', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20', '-pix_fmt', 'yuv420p');
  args.push('-c:a', 'aac', '-ar', '48000', '-ac', '2', '-shortest', '-movflags', '+faststart', outputPath);
  await runFfmpeg(args, event);
}

async function renderAudioTrack(clips, tempDir, event) {
  if (!clips.length) return null;
  const segments = [];
  for (let index = 0; index < clips.length; index += 1) {
    const clip = clips[index];
    const outputPath = path.join(tempDir, `audio-${String(index).padStart(3, '0')}.m4a`);
    const start = Math.max(0, Number(clip.trimStart) || 0);
    const duration = clipSourceDuration(clip);
    const args = ['-y', '-ss', String(start), '-t', String(duration), '-i', clip.path, '-vn'];
    const af = audioFilters(clip);
    if (af) args.push('-af', af);
    args.push('-c:a', 'aac', '-ar', '48000', '-ac', '2', outputPath);
    await runFfmpeg(args, event);
    segments.push(outputPath);
  }

  const listPath = path.join(tempDir, 'audio-list.txt');
  fs.writeFileSync(listPath, segments.map(concatLine).join(os.EOL), 'utf8');
  const mergedPath = path.join(tempDir, 'audio-track.m4a');
  await runFfmpeg(['-y', '-f', 'concat', '-safe', '0', '-i', listPath, '-c', 'copy', mergedPath], event);
  return mergedPath;
}

function escapeDrawText(value) {
  return String(value)
    .replace(/\\/g, '\\\\')
    .replace(/:/g, '\\:')
    .replace(/'/g, "\\'")
    .replace(/,/g, '\\,')
    .replace(/\[/g, '\\[')
    .replace(/\]/g, '\\]');
}

function textVideoFilter(texts) {
  return (texts || []).map(text => {
    const x = Math.max(0, Math.min(100, Number(text.x) || 50));
    const y = Math.max(0, Math.min(100, Number(text.y) || 18));
    const size = Math.max(12, Math.min(160, Number(text.size) || 42));
    const color = String(text.color || '#ffffff').replace('#', '');
    return `drawtext=text='${escapeDrawText(text.text)}':expansion=none:fontsize=${size}:fontcolor=${color}:borderw=2:bordercolor=black@0.75:x=w*${(x / 100).toFixed(4)}-text_w/2:y=h*${(y / 100).toFixed(4)}-text_h/2`;
  }).join(',');
}

async function finalizeTimeline(baseVideo, audioTrack, texts, destination, event) {
  const hasText = Array.isArray(texts) && texts.length > 0;
  if (!audioTrack && !hasText) {
    fs.copyFileSync(baseVideo, destination);
    return;
  }

  const args = ['-y', '-i', baseVideo];
  if (audioTrack) args.push('-i', audioTrack);
  const filters = [];
  let videoMap = '0:v:0';
  let audioMap = '0:a:0';

  if (hasText) {
    filters.push(`[0:v]${textVideoFilter(texts)}[vout]`);
    videoMap = '[vout]';
  }
  if (audioTrack) {
    filters.push('[0:a][1:a]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[aout]');
    audioMap = '[aout]';
  }
  if (filters.length) args.push('-filter_complex', filters.join(';'));
  args.push('-map', videoMap, '-map', audioMap);
  if (hasText) args.push('-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20');
  else args.push('-c:v', 'copy');
  args.push('-c:a', 'aac', '-ar', '48000', '-ac', '2', '-movflags', '+faststart', destination);
  await runFfmpeg(args, event);
}

ipcMain.handle('export-timeline', async (event, project) => {
  const clips = Array.isArray(project?.clips) ? project.clips : [];
  const videoClips = clips.filter(clip => clip.kind !== 'audio');
  const audioClips = clips.filter(clip => clip.kind === 'audio');
  if (!videoClips.length) throw new Error('No hay clips de video o imágenes en la pista V1.');

  const result = await dialog.showSaveDialog({
    title: 'Exportar proyecto',
    defaultPath: 'Ziiom-Video-Export.mp4',
    filters: [{ name: 'Video MP4', extensions: ['mp4'] }]
  });
  if (result.canceled || !result.filePath) return null;

  const tempDir = fs.mkdtempSync(path.join(app.getPath('temp'), 'ziiom-video-export-'));
  try {
    const segments = [];
    for (let index = 0; index < videoClips.length; index += 1) {
      event.sender.send('export-log', `Renderizando clip ${index + 1} de ${videoClips.length}\n`);
      const segmentPath = path.join(tempDir, `video-${String(index).padStart(3, '0')}.mp4`);
      await renderVideoSegment(videoClips[index], segmentPath, event);
      segments.push(segmentPath);
    }

    const listPath = path.join(tempDir, 'video-list.txt');
    fs.writeFileSync(listPath, segments.map(concatLine).join(os.EOL), 'utf8');
    const baseVideo = path.join(tempDir, 'timeline-base.mp4');
    await runFfmpeg(['-y', '-f', 'concat', '-safe', '0', '-i', listPath, '-c', 'copy', baseVideo], event);

    const audioTrack = await renderAudioTrack(audioClips, tempDir, event);
    await finalizeTimeline(baseVideo, audioTrack, project.texts || [], result.filePath, event);
    return result.filePath;
  } finally {
    try { fs.rmSync(tempDir, { recursive: true, force: true }); } catch {}
  }
});
