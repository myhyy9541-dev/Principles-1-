const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('wasel', {
  startRoom: () => ipcRenderer.invoke('start-room'),
  lanInfo: () => ipcRenderer.invoke('lan-info'),
  onRoomFound: fn => ipcRenderer.on('room-found', (_event, data) => fn(data)),
  onRoomStarted: fn => ipcRenderer.on('room-started', (_event, data) => fn(data)),
  onNotice: fn => ipcRenderer.on('notice', (_event, data) => fn(data))
});
