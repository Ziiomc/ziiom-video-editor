const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktop', {
  pickMedia: () => ipcRenderer.invoke('pick-media'),
  saveProject: data => ipcRenderer.invoke('save-project', data),
  openProject: () => ipcRenderer.invoke('open-project'),
  exportClip: clip => ipcRenderer.invoke('export-clip', clip),
  onExportLog: callback => ipcRenderer.on('export-log', (_event, value) => callback(value))
});
