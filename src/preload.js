const { contextBridge, ipcRenderer } = require('electron');

const subscribe = (channel, callback) => {
  if (typeof callback !== 'function') return () => {};
  const listener = (_event, data) => callback(data);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
};

contextBridge.exposeInMainWorld('wasel', {
  startPeer: () => ipcRenderer.invoke('start-peer'),
  lanInfo: () => ipcRenderer.invoke('lan-info'),
  onPeerFound: callback => subscribe('peer-found', callback),
  onPeerStarted: callback => subscribe('peer-started', callback),
  onNotice: callback => subscribe('notice', callback),
  listBluetoothDevices: () => ipcRenderer.invoke('bluetooth-list-ports'),
  connectBluetooth: port => ipcRenderer.invoke('bluetooth-connect-port', port),
  sendBluetoothFrame: frame => ipcRenderer.invoke('bluetooth-send-frame', frame),
  disconnectBluetooth: () => ipcRenderer.invoke('bluetooth-disconnect'),
  onBluetoothEvent: callback => subscribe('bluetooth-event', callback)
});
