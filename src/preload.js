const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktop', {
  pickMedia: filter => ipcRenderer.invoke('pick-media', filter),
  saveProject: data => ipcRenderer.invoke('save-project', data),
  openProject: () => ipcRenderer.invoke('open-project'),
  exportTimeline: project => ipcRenderer.invoke('export-timeline', project),
  onExportLog: callback => ipcRenderer.on('export-log', (_event, value) => callback(value))
});
