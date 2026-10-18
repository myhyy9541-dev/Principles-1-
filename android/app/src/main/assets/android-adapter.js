(() => {
  window.FURSAN_ANDROID = true;
  const listen = (name, callback) => window.addEventListener(name, event => callback(event.detail));
  window.wasel = {
    onPeerFound: callback => listen('fursan-peer-found', callback),
    onPeerStarted: () => {},
    onNotice: callback => listen('fursan-notice', callback),
    lanInfo: async () => ({ hostname: 'هاتف Android', addresses: [], port: 43821 }),
    startPeer: async () => ({ ok: false, android: true })
  };
  window.addEventListener('DOMContentLoaded', () => {
    const folderButton = document.getElementById('folderBtn');
    if (folderButton) folderButton.classList.add('hidden');
    const folderInput = document.getElementById('folderInput');
    if (folderInput) folderInput.remove();
  });
})();
