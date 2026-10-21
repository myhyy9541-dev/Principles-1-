(() => {
  window.FURSAN_ANDROID = true;
  const listen = (name, callback) => {
    const handler = event => callback(event.detail);
    window.addEventListener(name, handler);
    return () => window.removeEventListener(name, handler);
  };
  window.wasel = {
    onPeerFound: callback => listen('fursan-peer-found', callback),
    onPeerStarted: () => {},
    onNotice: callback => listen('fursan-notice', callback),
    lanInfo: async () => ({ hostname: 'هاتف Android', addresses: [], port: 43821 }),
    startPeer: async () => ({ ok: false, android: true }),
    startBluetooth: () => window.FursanBluetooth?.start() || 'unavailable',
    listBluetoothDevices: () => {
      try { return JSON.parse(window.FursanBluetooth?.listBondedDevices() || '[]'); } catch (_) { return []; }
    },
    connectBluetooth: address => window.FursanBluetooth?.connect(address) || 'unavailable',
    sendBluetoothFrame: (address, frame) => window.FursanBluetooth?.send(address, JSON.stringify(frame)) || false,
    disconnectBluetooth: address => window.FursanBluetooth?.disconnect(address),
    stopBluetooth: () => window.FursanBluetooth?.stop(),
    onBluetoothEvent: callback => listen('fursan-bt-event', callback),
    connectWifiPeer: (address, port, instanceId) => window.FursanWifiPeer?.connect(address, String(port), instanceId) || 'unavailable',
    getLocalWifiAddresses: () => window.FursanWifiPeer?.localAddresses() || '',
    connectWifiPeerManually: address => window.FursanWifiPeer?.connectManual(address) || 'unavailable',
    createWifiPairingQr: () => window.FursanWifiPeer?.createPairingQr() || '{"error":"تعذر الوصول إلى مُنشئ QR."}',
    scanWifiPairingQr: () => window.FursanWifiPeer?.scanPairingQr() || 'unavailable',
    sendWifiFrame: (address, frame) => window.FursanWifiPeer?.send(address, JSON.stringify(frame)) || false,
    disconnectWifiPeer: address => window.FursanWifiPeer?.disconnect(address),
    onWifiEvent: callback => listen('fursan-wifi-event', callback)
  };
  window.addEventListener('DOMContentLoaded', () => {
    const folderButton = document.getElementById('folderBtn');
    if (folderButton) folderButton.classList.add('hidden');
    const folderInput = document.getElementById('folderInput');
    if (folderInput) folderInput.remove();
  });
})();
