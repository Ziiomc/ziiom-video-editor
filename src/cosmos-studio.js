(() => {
  'use strict';
  const api = window.cosmosDesktop;
  const el = id => document.getElementById(id);
  const studio = {
    mode: 'text2video',
    imageName: '',
    working: false,
    selectedVideo: null,
    connected: false
  };

  function cleanError(error) {
    return String(error?.message || error || 'Ocurrió un error desconocido.')
      .replace(/^Error invoking remote method '[^']+': Error:\s*/i, '')
      .replace(/^Error:\s*/i, '');
  }
  function showConnection(message, isError = false) {
    const target = el('cosmosConnectionResult');
    target.textContent = message;
    target.classList.toggle('error', isError);
  }
  function showProgress(message, phase = 'idle') {
    el('cosmosProgressText').textContent = message;
    el('cosmosProgress').classList.toggle('busy', ['upload', 'processing'].includes(phase));
    el('cosmosProgress').classList.toggle('error', phase === 'error');
  }
  function setBusy(value) {
    studio.working = value;
    el('cosmosGenerate').disabled = value;
    el('cosmosCancel').classList.toggle('hidden', !value);
    document.querySelectorAll('[data-cosmos-mode]').forEach(button => { button.disabled = value; });
  }
  function fileUrl(filePath) {
    return 'file:///' + String(filePath).replace(/\\/g, '/').split('/')
      .map((part, index) => index === 0 ? part : encodeURIComponent(part)).join('/');
  }
  function chooseMode(mode) {
    if (studio.working) return;
    studio.mode = mode;
    document.querySelectorAll('[data-cosmos-mode]').forEach(button => {
      const selected = button.dataset.cosmosMode === mode;
      button.classList.toggle('selected', selected);
      button.setAttribute('aria-pressed', String(selected));
    });
    el('cosmosImageWrap').classList.toggle('hidden', mode !== 'image2video');
  }
  function setView(view) {
    const studioView = view === 'studio';
    document.body.classList.toggle('studio-mode', studioView);
    document.body.classList.toggle('editor-mode', !studioView);
    el('openStudioView').classList.toggle('active', studioView);
    el('openEditorView').classList.toggle('active', !studioView);
    el('openStudioView').setAttribute('aria-pressed', String(studioView));
    el('openEditorView').setAttribute('aria-pressed', String(!studioView));
    if (!studioView) window.dispatchEvent(new Event('resize'));
  }
  window.ziiomSetWorkspace = setView;
  el('openStudioView').addEventListener('click', () => setView('studio'));
  el('openEditorView').addEventListener('click', () => setView('editor'));

  async function chooseImage() {
    if (studio.working) return;
    try {
      const file = await api.pickImage();
      if (!file) return;
      studio.imageName = file.name;
      el('cosmosReferencePreview').src = file.preview;
      el('cosmosReferencePreview').classList.remove('hidden');
      el('cosmosImagePlaceholder').classList.add('hidden');
      el('cosmosImageName').textContent = file.name;
      showProgress('Imagen de referencia cargada: ' + file.name);
    } catch (error) { showProgress(cleanError(error), 'error'); }
  }
  const upload = el('cosmosImageUpload');
  upload.tabIndex = 0;
  upload.setAttribute('role', 'button');
  upload.setAttribute('aria-label', 'Seleccionar una imagen de referencia');
  upload.addEventListener('click', chooseImage);
  upload.addEventListener('keydown', event => {
    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); void chooseImage(); }
  });
  el('cosmosClearImage').addEventListener('click', async () => {
    try {
      await api.clearImage();
      studio.imageName = '';
      el('cosmosReferencePreview').removeAttribute('src');
      el('cosmosReferencePreview').classList.add('hidden');
      el('cosmosImagePlaceholder').classList.remove('hidden');
      el('cosmosImageName').textContent = 'Sin imagen';
    } catch (error) { showProgress(cleanError(error), 'error'); }
  });

  document.querySelectorAll('[data-cosmos-mode]').forEach(button =>
    button.addEventListener('click', () => chooseMode(button.dataset.cosmosMode)));
  el('cosmosPrompt').addEventListener('input', () => {
    el('cosmosPromptCount').textContent = el('cosmosPrompt').value.length + '/4000';
  });
  el('cosmosPromptSample').addEventListener('click', () => {
    el('cosmosPrompt').value = 'Epic cinematic battle between a colossal alien beast and a biomechanical creature on a distant planet. Dynamic camera, dramatic lighting, dust, realistic motion, high detail.';
    el('cosmosPrompt').dispatchEvent(new Event('input'));
    el('cosmosPrompt').focus();
  });

  async function refreshStatus() {
    try {
      const response = await api.status();
      studio.connected = response.keyConfigured;
      el('cosmosKeyBadge').textContent = response.keyConfigured ? 'CLAVE LISTA' : 'SIN CLAVE';
      el('cosmosKeyBadge').classList.toggle('key-missing', !response.keyConfigured);
      if (!response.keyConfigured) showConnection('No hay API Key en esta sesión. Puedes obtenerla en NVIDIA Build.');
    } catch (error) { showConnection(cleanError(error), true); }
  }
  el('cosmosSaveKey').addEventListener('click', async () => {
    const keyField = el('cosmosKey');
    try {
      await api.setKey(keyField.value);
      keyField.value = '';
      await refreshStatus();
      showConnection('Clave conectada solo a esta sesión. Comprueba la ruta API antes de generar.');
    } catch (error) { showConnection(cleanError(error), true); }
  });
  el('cosmosKey').addEventListener('keydown', event => {
    if (event.key === 'Enter') { event.preventDefault(); el('cosmosSaveKey').click(); }
  });
  el('cosmosForgetKey').addEventListener('click', async () => {
    try {
      await api.clearKey();
      await refreshStatus();
      showConnection('Clave temporal descartada. Si configuraste NVIDIA_API_KEY en Windows, seguirá disponible.');
    } catch (error) { showConnection(cleanError(error), true); }
  });
  el('cosmosTest').addEventListener('click', async () => {
    const button = el('cosmosTest');
    button.disabled = true;
    showConnection('Probando la ruta NVIDIA sin ejecutar generación...');
    try {
      const response = await api.test();
      showConnection(response.detail, !response.available);
    } catch (error) { showConnection(cleanError(error), true); }
    finally { button.disabled = false; }
  });

  function showVideo(video) {
    studio.selectedVideo = video;
    const media = el('cosmosVideo');
    media.pause();
    media.src = fileUrl(video.path);
    media.classList.remove('hidden');
    el('cosmosEmpty').classList.add('hidden');
    el('cosmosVideoBadge').textContent = (video.seconds || '?') + ' S · ' + String(video.aspect || '16_9').replaceAll('_', ':');
    el('cosmosAddToEditor').disabled = false;
    media.load();
  }
  el('cosmosVideo').addEventListener('error', () => {
    if (studio.selectedVideo) {
      showProgress('El MP4 se guardó, pero este reproductor puede no admitir el códec VP9. Ábrelo desde la carpeta de resultados.', 'error');
    }
  });
  async function refreshLibrary() {
    const list = el('cosmosLibrary');
    try {
      const videos = await api.library();
      list.replaceChildren();
      if (!videos.length) {
        const empty = document.createElement('p');
        empty.className = 'cosmos-muted';
        empty.textContent = 'Todavía no hay escenas generadas en este equipo.';
        list.appendChild(empty);
        return;
      }
      videos.forEach(video => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'cosmos-library-item';
        const icon = document.createElement('span');
        icon.className = 'cosmos-library-icon';
        icon.textContent = '▶';
        const details = document.createElement('span');
        const title = document.createElement('strong');
        title.textContent = video.prompt || video.name;
        const sub = document.createElement('span');
        const date = new Date(video.createdAt);
        sub.textContent = (Number.isFinite(date.getTime()) ? date.toLocaleString('es-CL') : '') + ' · ' + (video.seconds || '?') + ' s';
        details.append(title, sub);
        button.append(icon, details);
        button.title = video.name;
        button.addEventListener('click', () => {
          showVideo(video);
          showProgress('Escena recuperada desde tu biblioteca local.');
        });
        list.appendChild(button);
      });
    } catch (error) { showProgress(cleanError(error), 'error'); }
  }
  el('cosmosRefreshLibrary').addEventListener('click', refreshLibrary);
  el('cosmosOpenFolder').addEventListener('click', async () => {
    try { await api.openFolder(); } catch (error) { showProgress(cleanError(error), 'error'); }
  });
  el('cosmosOpenBuildTop').addEventListener('click', async () => {
    try { await api.openBuild(); } catch (error) { showConnection(cleanError(error), true); }
  });

  el('cosmosGenerate').addEventListener('click', async () => {
    if (studio.working) return;
    const prompt = el('cosmosPrompt').value.trim();
    if (!prompt) { showProgress('Escribe un prompt antes de generar.', 'error'); el('cosmosPrompt').focus(); return; }
    if (studio.mode === 'image2video' && !studio.imageName) { showProgress('Selecciona una imagen de referencia.', 'error'); return; }
    const options = {
      mode: studio.mode,
      prompt,
      tier: el('cosmosTier').value,
      aspect: el('cosmosAspect').value,
      seconds: Number(el('cosmosSeconds').value),
      steps: Number(el('cosmosSteps').value),
      seed: el('cosmosSeed').value.trim()
    };
    setBusy(true);
    showProgress('Preparando escena...', 'upload');
    try {
      const video = await api.generate(options);
      showVideo(video);
      showProgress('Escena generada y guardada como MP4.', 'complete');
      await refreshLibrary();
    } catch (error) { showProgress(cleanError(error), 'error'); }
    finally { setBusy(false); }
  });
  el('cosmosCancel').addEventListener('click', async () => {
    try {
      const result = await api.cancel();
      showProgress(result.note || 'Solicitud de cancelación enviada.', 'error');
    } catch (error) { showProgress(cleanError(error), 'error'); }
  });
  el('cosmosAddToEditor').addEventListener('click', () => {
    if (!studio.selectedVideo) return;
    if (typeof window.ziiomAddGeneratedVideo !== 'function') {
      showProgress('No fue posible conectar con la biblioteca del editor.', 'error');
      return;
    }
    window.ziiomAddGeneratedVideo(studio.selectedVideo);
    setView('editor');
  });
  api.onProgress(detail => { if (studio.working) showProgress(detail.message, detail.phase); });
  chooseMode('text2video');
  void refreshStatus();
  void refreshLibrary();
})();
