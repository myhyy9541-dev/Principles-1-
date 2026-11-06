(() => {
  const callbacks = new Map();
  const params = new URLSearchParams(location.search);
  const windowInfo = Object.freeze({
    id: params.get('windowId') || 'legacy',
    role: params.get('view') || 'monitor',
    targetId: params.get('targetId') || '',
    targetName: params.get('targetName') || '',
    callId: params.get('callId') || ''
  });
  const emit = (name, data) => { for (const fn of callbacks.get(name) || []) { try { fn(data); } catch (error) { console.error(error); } } };
  async function request(path, options = {}) {
    const response = await fetch(path, { cache: 'no-store', ...options });
    const value = await response.json();
    if (!response.ok || value?.ok === false) throw new Error(value?.error || `HTTP ${response.status}`);
    return value;
  }
  const post = (path, body) => request(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  window.wasel = {
    windowInfo,
    startRoom: () => request('/api/start-room', { method: 'POST' }),
    startPeer: (clientId, name) => post('/api/start-peer', { clientId, name }),
    stopPeer: () => request('/api/stop-peer', { method: 'POST' }),
    lanInfo: () => request('/api/lan-info'),
    getSettings: () => request('/api/settings'),
    saveSettings: settings => post('/api/settings', { settings }),
    getPrinters: () => request('/api/printers'),
    setPrinterPermission: permission => post('/api/printer-permissions', permission),
    printLocal: async ({ blob, senderClientId, printerId, filename }) => {
      const response = await fetch('/api/print-local', { method: 'POST', headers: {
        'Content-Type': blob.type || 'application/octet-stream', 'X-Wasel-Sender': senderClientId,
        'X-Wasel-Printer': printerId, 'X-Wasel-Filename': encodeURIComponent(filename)
      }, body: blob, cache: 'no-store' });
      const value = await response.json();
      if (!response.ok || value?.ok === false) throw new Error(value?.error || `HTTP ${response.status}`);
      return value;
    },
    chooseSaveDirectory: () => request('/api/choose-save-directory', { method: 'POST' }),
    openWindow: options => post('/api/window', { action: 'open', ...options }),
    showWindow: () => post('/api/window', { action: 'show', windowId: windowInfo.id }),
    hideWindow: () => post('/api/window', { action: 'hide', windowId: windowInfo.id }),
    closeWindow: () => post('/api/window', { action: 'close', windowId: windowInfo.id }),
    unregisterWindow: () => post('/api/window', { action: 'unregister', windowId: windowInfo.id }),
    onRoomFound: fn => { const list = callbacks.get('room-found') || []; list.push(fn); callbacks.set('room-found', list); },
    onPeerFound: fn => { const list = callbacks.get('peer-found') || []; list.push(fn); callbacks.set('peer-found', list); },
    onRoomStarted: fn => { const list = callbacks.get('room-started') || []; list.push(fn); callbacks.set('room-started', list); },
    onNotice: fn => { const list = callbacks.get('notice') || []; list.push(fn); callbacks.set('notice', list); },
    onSettingsUpdated: fn => { const list = callbacks.get('settings-updated') || []; list.push(fn); callbacks.set('settings-updated', list); }
  };
  async function poll() {
    try { for (const item of await request(`/api/events?windowId=${encodeURIComponent(windowInfo.id)}`)) emit(item.type, item.data); }
    catch (error) { /* Retry when the local Java host is ready. */ }
    setTimeout(poll, 700);
  }
  poll();
})();
