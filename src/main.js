const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

function createWindow() {
  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1050,
    minHeight: 680,
    backgroundColor: '#0b0f17',
    titleBarStyle: 'hiddenInset',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      webSecurity: false
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

ipcMain.handle('pick-media', async () => {
  const result = await dialog.showOpenDialog({
    title: 'Importar archivos',
    properties: ['openFile', 'multiSelections'],
    filters: [
      { name: 'Multimedia', extensions: ['mp4','mov','mkv','webm','avi','mp3','wav','m4a','aac','ogg','png','jpg','jpeg','webp'] },
      { name: 'Todos', extensions: ['*'] }
    ]
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

ipcMain.handle('export-clip', async (event, clip) => {
  if (!clip?.path) throw new Error('No hay un clip de video seleccionado.');
  const result = await dialog.showSaveDialog({
    title: 'Exportar video',
    defaultPath: `${path.parse(clip.name || 'video').name}-editado.mp4`,
    filters: [{ name: 'Video MP4', extensions: ['mp4'] }]
  });
  if (result.canceled || !result.filePath) return null;

  const ffmpeg = getFfmpegPath();
  const start = Math.max(0, Number(clip.trimStart || 0));
  const end = Number(clip.trimEnd || 0);
  const duration = end > start ? end - start : null;
  const speed = Math.max(0.25, Math.min(4, Number(clip.speed || 1)));
  const volume = Math.max(0, Math.min(2, Number(clip.volume ?? 1)));
  const rotation = Number(clip.rotation || 0);

  const vf = [];
  if (rotation === 90) vf.push('transpose=1');
  if (rotation === 180) vf.push('transpose=1,transpose=1');
  if (rotation === 270) vf.push('transpose=2');
  if (Math.abs(speed - 1) > 0.001) vf.push(`setpts=${(1 / speed).toFixed(6)}*PTS`);

  const af = [];
  if (Math.abs(speed - 1) > 0.001) {
    let remaining = speed;
    while (remaining > 2) { af.push('atempo=2'); remaining /= 2; }
    while (remaining < 0.5) { af.push('atempo=0.5'); remaining /= 0.5; }
    af.push(`atempo=${remaining.toFixed(6)}`);
  }
  if (Math.abs(volume - 1) > 0.001) af.push(`volume=${volume.toFixed(3)}`);

  const args = ['-y'];
  if (start > 0) args.push('-ss', String(start));
  args.push('-i', clip.path);
  if (duration) args.push('-t', String(duration));
  if (vf.length) args.push('-vf', vf.join(','));
  if (af.length) args.push('-af', af.join(','));
  args.push('-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20', '-c:a', 'aac', '-movflags', '+faststart', result.filePath);

  return await new Promise((resolve, reject) => {
    const proc = spawn(ffmpeg, args, { windowsHide: true });
    let stderr = '';
    proc.stderr.on('data', d => {
      stderr += d.toString();
      event.sender.send('export-log', d.toString());
    });
    proc.on('error', reject);
    proc.on('close', code => {
      if (code === 0) resolve(result.filePath);
      else reject(new Error(stderr.split('\n').slice(-8).join('\n') || `FFmpeg terminó con código ${code}`));
    });
  });
});
