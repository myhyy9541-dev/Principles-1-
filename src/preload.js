const { contextBridge, ipcRenderer } = require('electron');
const { io } = require('socket.io-client');
let socket;
contextBridge.exposeInMainWorld('wasel', {
  startRoom: () => ipcRenderer.invoke('start-room'),
  lanInfo: () => ipcRenderer.invoke('lan-info'),
  onRoomFound: fn => ipcRenderer.on('room-found', (_e, data) => fn(data)),
  onRoomStarted: fn => ipcRenderer.on('room-started', (_e, data) => fn(data)),
  onNotice: fn => ipcRenderer.on('notice', (_e, data) => fn(data)),
  connectRoom: (url, name, handlers) => {
    socket?.disconnect();
    socket = io(url, { transports: ['websocket', 'polling'], timeout: 5000, reconnection: true });
    for (const event of ['connect', 'connect_error', 'history', 'members', 'system-message', 'chat-message', 'file-message', 'signal', 'notice'])
      socket.on(event, data => { if (event === 'connect') { socket.emit('join', { name }); handlers(event, { id: socket.id }); } else handlers(event, data); });
    return true;
  },
  send: (event, data) => socket?.emit(event, data),
  disconnect: () => socket?.disconnect()
});
