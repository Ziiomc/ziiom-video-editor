'use strict';

// Pure, dependency-free request validation for the NVIDIA Cosmos3 Nano API.
// Source: https://build.nvidia.com/nvidia/cosmos3-nano/modelcard
const COSMOS_ENDPOINT = 'https://ai.api.nvidia.com/v1/cosmos/nvidia/cosmos3-nano';
const MODES = new Set(['text2video', 'image2video']);
const TIERS = new Set(['256', '480', '720']);
const ASPECTS = new Set(['16_9', '9_16', '1_1', '4_3', '3_4']);
const FRAME_LIMITS = { '256': 397, '480': 297, '720': 197 };

function prepareCosmosRequest(options, image, randomSeed = Math.floor(Math.random() * 2147483647)) {
  if (!options || !MODES.has(options.mode)) throw new Error('Selecciona Texto a video o Imagen a video.');
  const prompt = String(options.prompt || '').trim();
  if (!prompt || prompt.length > 4000) throw new Error('Escribe un prompt de entre 1 y 4000 caracteres.');
  const tier = String(options.tier || '480');
  const aspect = String(options.aspect || '16_9');
  if (!TIERS.has(tier) || !ASPECTS.has(aspect)) throw new Error('Formato o resolución no admitidos por Cosmos.');
  const seconds = Number(options.seconds);
  if (!Number.isInteger(seconds) || seconds < 1 || seconds > 8) throw new Error('La duración debe estar entre 1 y 8 segundos.');
  const numFrames = 1 + seconds * 24;
  if (numFrames < 25 || numFrames > FRAME_LIMITS[tier]) throw new Error('La duración elegida supera el límite de esta resolución.');
  const steps = Number(options.steps ?? 35);
  if (!Number.isInteger(steps) || steps < 10 || steps > 60) throw new Error('Los pasos deben estar entre 10 y 60.');
  let seed = randomSeed;
  if (options.seed !== undefined && options.seed !== null && String(options.seed).trim() !== '') {
    seed = Number(options.seed);
    if (!Number.isInteger(seed) || seed < 0 || seed > 2147483647) throw new Error('La semilla debe ser un entero entre 0 y 2147483647.');
  }
  const body = {
    model_mode: options.mode,
    prompt,
    resolution: tier + '_' + aspect,
    num_frames: numFrames,
    num_inference_steps: steps,
    fps: 24,
    seed
  };
  if (options.mode === 'image2video') {
    if (!image || !Buffer.isBuffer(image.bytes) || !['image/jpeg', 'image/png', 'image/webp'].includes(image.mime)) {
      throw new Error('Primero selecciona una imagen JPG, PNG o WebP.');
    }
    body.input_reference = 'data:' + image.mime + ';base64,' + image.bytes.toString('base64');
  }
  return body;
}

function describeCosmosError(status, responseText = '') {
  const excerpt = String(responseText).trim().replace(/\s+/g, ' ').slice(0, 240);
  if (status === 404) {
    return 'NVIDIA respondió HTTP 404: la ruta pública de Cosmos3 Nano no está disponible para esta cuenta o en este momento. La interfaz funciona, pero no se puede generar hasta que NVIDIA habilite el endpoint. Usa «Abrir NVIDIA Build» para probar el Playground.';
  }
  if (status === 401 || status === 403) return 'NVIDIA rechazó las credenciales (HTTP ' + status + '). Revisa tu API Key y los permisos de tu organización.';
  if (status === 429) return 'NVIDIA limitó las solicitudes (HTTP 429). No vuelvas a generar inmediatamente: espera a que se libere la cuota.';
  if (status === 422 || status === 400) return 'NVIDIA rechazó los parámetros (HTTP ' + status + '). ' + excerpt;
  if (status >= 500) return 'NVIDIA tuvo un error de servicio (HTTP ' + status + '). ' + excerpt;
  return 'NVIDIA devolvió HTTP ' + status + '. ' + excerpt;
}

function decodeCosmosMp4(result) {
  if (!result || typeof result.b64_video !== 'string' || !result.b64_video.trim()) {
    throw new Error('NVIDIA no devolvió b64_video. No se guardó ningún archivo vacío.');
  }
  const encoded = result.b64_video.replace(/^data:video\/mp4;base64,/, '').replace(/\s+/g, '');
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(encoded) || encoded.length % 4 !== 0) {
    throw new Error('La respuesta de NVIDIA no contiene un MP4 codificado correctamente.');
  }
  const bytes = Buffer.from(encoded, 'base64');
  if (bytes.length < 32 || bytes.subarray(4, 8).toString('ascii') !== 'ftyp') {
    throw new Error('La respuesta de NVIDIA no contiene un contenedor MP4 válido.');
  }
  return bytes;
}

module.exports = { COSMOS_ENDPOINT, prepareCosmosRequest, describeCosmosError, decodeCosmosMp4 };
