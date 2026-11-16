'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { prepareCosmosRequest, describeCosmosError, decodeCosmosMp4, COSMOS_ENDPOINT } = require('../src/cosmos-core');

const defaultOptions = { mode: 'text2video', prompt: 'A cinematic robot in a futuristic city', tier: '480', aspect: '16_9', seconds: 3, steps: 35, seed: '' };

test('uses the official route without guessing alternate endpoints', () => {
  assert.equal(COSMOS_ENDPOINT, 'https://ai.api.nvidia.com/v1/cosmos/nvidia/cosmos3-nano');
});
test('builds a correct 3-second, 24 fps cinematic generation request', () => {
  const request = prepareCosmosRequest(defaultOptions, null, 42);
  assert.deepEqual(request, {
    model_mode: 'text2video', prompt: defaultOptions.prompt,
    resolution: '480_16_9', num_frames: 73, num_inference_steps: 35, fps: 24, seed: 42
  });
});
test('correctly sends image conditioning as input_reference', () => {
  const request = prepareCosmosRequest({ ...defaultOptions, mode: 'image2video', aspect: '9_16' },
    { mime: 'image/png', bytes: Buffer.from('abc') }, 23);
  assert.equal(request.resolution, '480_9_16');
  assert.equal(request.input_reference, 'data:image/png;base64,YWJj');
});
test('requires an image for image-to-video', () => {
  assert.throws(() => prepareCosmosRequest({ ...defaultOptions, mode: 'image2video' }, null), /imagen JPG/);
});
test('validates limits and does not manufacture unsupported aspect ratios', () => {
  assert.throws(() => prepareCosmosRequest({ ...defaultOptions, aspect: '21_9' }, null), /Formato/);
  assert.throws(() => prepareCosmosRequest({ ...defaultOptions, seconds: 0 }, null), /duración/);
  assert.throws(() => prepareCosmosRequest({ ...defaultOptions, seed: '-1' }, null), /semilla/);
});
test('explains the NVIDIA 404 instead of reporting a generated video', () => {
  assert.match(describeCosmosError(404, '404 page not found'), /HTTP 404/);
  assert.match(describeCosmosError(429, ''), /HTTP 429/);
});
test('never saves a blank or invalid MP4 response', () => {
  assert.throws(() => decodeCosmosMp4({ b64_video: '' }), /b64_video/);
  assert.throws(() => decodeCosmosMp4({ b64_video: Buffer.from('not a movie').toString('base64') }), /MP4 válido/);
});
test('accepts a correctly framed MP4 container', () => {
  const buffer = Buffer.alloc(64);
  buffer.write('ftyp', 4, 'ascii');
  assert.deepEqual(decodeCosmosMp4({ b64_video: buffer.toString('base64') }), buffer);
});
