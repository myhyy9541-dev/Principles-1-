const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('wasel', {
  startPeer: () => ipcRenderer.invoke('start-peer'),
  lanInfo: () => ipcRenderer.invoke('lan-info'),
  onPeerFound: fn => ipcRenderer.on('peer-found', (_event, data) => fn(data)),
  onPeerStarted: fn => ipcRenderer.on('peer-started', (_event, data) => fn(data)),
  onNotice: fn => ipcRenderer.on('notice', (_event, data) => fn(data))
});
