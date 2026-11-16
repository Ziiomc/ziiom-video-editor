'use strict';

const { app, dialog, ipcMain, shell } = require('electron');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { COSMOS_ENDPOINT, prepareCosmosRequest, describeCosmosError, decodeCosmosMp4 } = require('./cosmos-core');

const MAX_IMAGE_BYTES = 12 * 1024 * 1024;
const MAX_RESPONSE_BYTES = 256 * 1024 * 1024;
let sessionKey = '';
let selectedImage = null;
let activeGeneration = null;

const getKey = () => sessionKey || process.env.NVIDIA_API_KEY?.trim() || '';
const outputFolder = () => path.join(app.getPath('videos'), 'Ziiom Cosmos Studio');

function imageMime(buffer) {
  if (buffer.length >= 8 && buffer.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) return 'image/png';
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) return 'image/jpeg';
  if (buffer.length >= 12 && buffer.toString('ascii', 0, 4) === 'RIFF' && buffer.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
  return null;
}
function sendProgress(event, phase, message) {
  if (!event.sender.isDestroyed()) event.sender.send('cosmos:progress', { phase, message });
}
function getLibrary() {
  const folder = outputFolder();
  if (!fs.existsSync(folder)) return [];
  return fs.readdirSync(folder)
    .filter(name => /^cosmos-[0-9T-]+-[a-f0-9]{8}\.json$/.test(name))
    .sort().reverse().slice(0, 60)
    .flatMap(name => {
      try {
        const data = JSON.parse(fs.readFileSync(path.join(folder, name), 'utf8'));
        const safeMp4Name = path.basename(name, '.json') + '.mp4';
        const videoPath = path.join(folder, safeMp4Name);
        if (!fs.existsSync(videoPath)) return [];
        return [{ ...data, path: videoPath, name: safeMp4Name }];
      } catch { return []; }
    });
}
async function readResponseBounded(response) {
  const declared = Number(response.headers.get('content-length') || 0);
  if (declared > MAX_RESPONSE_BYTES) throw new Error('La respuesta excede el límite seguro de 256 MB.');
  const chunks = [];
  let total = 0;
  if (!response.body) return '';
  for await (const chunk of response.body) {
    total += chunk.byteLength;
    if (total > MAX_RESPONSE_BYTES) {
      await response.body.cancel().catch(() => {});
      throw new Error('La respuesta de NVIDIA excede 256 MB.');
    }
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString('utf8');
}

function registerCosmosIpc() {
  ipcMain.handle('cosmos:status', async () => ({
    keyConfigured: Boolean(getKey()),
    endpoint: COSMOS_ENDPOINT,
    generating: Boolean(activeGeneration),
    outputFolder: outputFolder()
  }));
  ipcMain.handle('cosmos:set-key', async (_event, entered) => {
    const value = String(entered || '').trim().replace(/^Bearer\s+/i, '');
    if (!value || value.length < 16 || value.length > 512 || /\s/.test(value)) {
      throw new Error('Introduce una NVIDIA API Key válida. No se guardará en ningún archivo.');
    }
    sessionKey = value;
    return { keyConfigured: true };
  });
  ipcMain.handle('cosmos:clear-key', async () => {
    sessionKey = '';
    return { keyConfigured: Boolean(getKey()) };
  });
  ipcMain.handle('cosmos:pick-image', async () => {
    const picked = await dialog.showOpenDialog({
      title: 'Imagen de referencia para Cosmos',
      properties: ['openFile'],
      filters: [{ name: 'Imágenes compatibles', extensions: ['jpg', 'jpeg', 'png', 'webp'] }]
    });
    if (picked.canceled || !picked.filePaths?.[0]) return null;
    const filePath = picked.filePaths[0];
    const stat = fs.statSync(filePath);
    if (!stat.isFile() || stat.size > MAX_IMAGE_BYTES) throw new Error('La imagen no puede superar 12 MB.');
    const bytes = fs.readFileSync(filePath);
    const mime = imageMime(bytes);
    if (!mime) throw new Error('Archivo inválido. Usa JPG, PNG o WebP.');
    selectedImage = { path: filePath, mime, bytes };
    return { name: path.basename(filePath), bytes: bytes.length, preview: 'data:' + mime + ';base64,' + bytes.toString('base64') };
  });
  ipcMain.handle('cosmos:clear-image', async () => { selectedImage = null; return true; });
  ipcMain.handle('cosmos:library', async () => getLibrary());
  ipcMain.handle('cosmos:open-folder', async () => {
    fs.mkdirSync(outputFolder(), { recursive: true });
    const message = await shell.openPath(outputFolder());
    if (message) throw new Error(message);
    return true;
  });
  ipcMain.handle('cosmos:open-build', async () => {
    await shell.openExternal('https://build.nvidia.com/nvidia/cosmos3-nano');
    return true;
  });
  ipcMain.handle('cosmos:test', async () => {
    const key = getKey();
    if (!key) throw new Error('Configura tu API Key en esta sesión para comprobar el acceso.');
    const response = await fetch(COSMOS_ENDPOINT, {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + key, Accept: 'application/json', 'Content-Type': 'application/json' },
      body: '{}', // Deliberately invalid; verifies routing without requesting inference.
      signal: AbortSignal.timeout(20000),
      redirect: 'error'
    });
    const body = (await response.text()).slice(0, 450);
    if (response.status === 404) return { available: false, detail: describeCosmosError(404, body) };
    if (response.status === 401 || response.status === 403) return { available: false, detail: describeCosmosError(response.status, body) };
    if ([400, 422].includes(response.status)) return {
      available: true,
      detail: 'El servidor Cosmos respondió a la prueba de ruta (HTTP ' + response.status + '). Esto no garantiza acceso a inferencia; genera una escena corta para verificarlo.'
    };
    return { available: response.ok, detail: response.ok
      ? 'NVIDIA respondió, pero la prueba sin generación no valida aún los permisos de inferencia.'
      : describeCosmosError(response.status, body) };
  });
  ipcMain.handle('cosmos:cancel', async () => {
    if (!activeGeneration) return { cancelled: false };
    activeGeneration.abort();
    return { cancelled: true, note: 'Se canceló la espera local. NVIDIA podría seguir procesando; no repitas automáticamente la solicitud.' };
  });
  ipcMain.handle('cosmos:generate', async (event, options) => {
    if (activeGeneration) throw new Error('Ya hay una escena en proceso. Termina o cancela la actual.');
    const key = getKey();
    if (!key) throw new Error('Configura primero tu NVIDIA API Key.');
    const request = prepareCosmosRequest(options, selectedImage, crypto.randomInt(0, 2147483647));
    const controller = new AbortController();
    activeGeneration = controller;
    const deadline = setTimeout(() => controller.abort(), 10 * 60 * 1000);
    try {
      sendProgress(event, 'upload', 'Enviando la escena a NVIDIA...');
      const startedAt = Date.now();
      const response = await fetch(COSMOS_ENDPOINT, {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + key, Accept: 'application/json', 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
        signal: controller.signal,
        redirect: 'error'
      });
      const requestId = response.headers.get('x-request-id') || response.headers.get('nvcf-reqid') || null;
      sendProgress(event, 'processing', 'Esperando respuesta del motor de NVIDIA...');
      const raw = await readResponseBounded(response);
      if (!response.ok) throw new Error(describeCosmosError(response.status, raw));
      let parsed;
      try { parsed = JSON.parse(raw); } catch { throw new Error('NVIDIA devolvió una respuesta que no es JSON. No se creó un MP4.'); }
      if (!parsed?.b64_video) {
        if (response.status === 202) throw new Error('NVIDIA aceptó un trabajo asíncrono, pero no devolvió el MP4. No lo reenvíes sin consultar el identificador ' + (requestId || 'de la solicitud') + '.');
        throw new Error('NVIDIA respondió sin un video MP4 en b64_video. No se generó ningún archivo.');
      }
      const video = decodeCosmosMp4(parsed);
      const folder = outputFolder();
      fs.mkdirSync(folder, { recursive: true });
      const base = 'cosmos-' + new Date().toISOString().replace(/[:.Z]/g, '-') + '-' + crypto.randomBytes(4).toString('hex');
      const destination = path.join(folder, base + '.mp4');
      const metadata = {
        id: base,
        createdAt: new Date().toISOString(),
        prompt: request.prompt,
        mode: request.model_mode,
        aspect: String(options.aspect || '16_9'),
        tier: String(options.tier || '480'),
        seconds: Number(options.seconds),
        frames: request.num_frames,
        fps: request.fps,
        seed: request.seed,
        steps: request.num_inference_steps,
        requestId,
        elapsedSeconds: Math.round((Date.now() - startedAt) / 1000)
      };
      fs.writeFileSync(destination, video, { flag: 'wx' });
      try { fs.writeFileSync(path.join(folder, base + '.json'), JSON.stringify(metadata, null, 2), { flag: 'wx' }); } catch { /* Keep the generated MP4 if metadata fails. */ }
      sendProgress(event, 'complete', 'Escena generada y guardada en Videos/Ziiom Cosmos Studio.');
      return { ...metadata, path: destination, name: base + '.mp4' };
    } catch (error) {
      if (controller.signal.aborted) {
        throw new Error('Se interrumpió la espera local. El trabajo remoto podría seguir ejecutándose: verifica tu cuenta antes de volver a enviarlo.');
      }
      throw error;
    } finally {
      clearTimeout(deadline);
      activeGeneration = null;
    }
  });
}

module.exports = { registerCosmosIpc };
