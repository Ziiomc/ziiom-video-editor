const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktop', {
  pickMedia: filter => ipcRenderer.invoke('pick-media', filter),
  saveProject: data => ipcRenderer.invoke('save-project', data),
  openProject: () => ipcRenderer.invoke('open-project'),
  exportTimeline: project => ipcRenderer.invoke('export-timeline', project),
  onExportLog: callback => ipcRenderer.on('export-log', (_event, value) => callback(value))
});

contextBridge.exposeInMainWorld('cosmosDesktop', {
  status: () => ipcRenderer.invoke('cosmos:status'),
  setKey: key => ipcRenderer.invoke('cosmos:set-key', key),
  clearKey: () => ipcRenderer.invoke('cosmos:clear-key'),
  pickImage: () => ipcRenderer.invoke('cosmos:pick-image'),
  clearImage: () => ipcRenderer.invoke('cosmos:clear-image'),
  test: () => ipcRenderer.invoke('cosmos:test'),
  generate: options => ipcRenderer.invoke('cosmos:generate', options),
  cancel: () => ipcRenderer.invoke('cosmos:cancel'),
  library: () => ipcRenderer.invoke('cosmos:library'),
  openFolder: () => ipcRenderer.invoke('cosmos:open-folder'),
  openBuild: () => ipcRenderer.invoke('cosmos:open-build'),
  onProgress: callback => {
    const listener = (_event, detail) => callback(detail);
    ipcRenderer.on('cosmos:progress', listener);
    return () => ipcRenderer.removeListener('cosmos:progress', listener);
  }
});
