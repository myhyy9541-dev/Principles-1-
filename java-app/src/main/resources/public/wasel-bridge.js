(() => {
  const callbacks = new Map();
  const emit = (name, data) => { for (const fn of callbacks.get(name) || []) { try { fn(data); } catch (error) { console.error(error); } } };
  async function request(path, options = {}) {
    const response = await fetch(path, { cache: 'no-store', ...options });
    const value = await response.json();
    if (!response.ok || value?.ok === false) throw new Error(value?.error || `HTTP ${response.status}`);
    return value;
  }
  window.wasel = {
    startRoom: () => request('/api/start-room', { method: 'POST' }),
    startPeer: (clientId, name) => request('/api/start-peer', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ clientId, name }) }),
    stopPeer: () => request('/api/stop-peer', { method: 'POST' }),
    lanInfo: () => request('/api/lan-info'),
    getSettings: () => request('/api/settings'),
    saveSettings: settings => request('/api/settings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ settings }) }),
    onRoomFound: fn => { const list = callbacks.get('room-found') || []; list.push(fn); callbacks.set('room-found', list); },
    onPeerFound: fn => { const list = callbacks.get('peer-found') || []; list.push(fn); callbacks.set('peer-found', list); },
    onRoomStarted: fn => { const list = callbacks.get('room-started') || []; list.push(fn); callbacks.set('room-started', list); },
    onNotice: fn => { const list = callbacks.get('notice') || []; list.push(fn); callbacks.set('notice', list); },
    onSettingsUpdated: fn => { const list = callbacks.get('settings-updated') || []; list.push(fn); callbacks.set('settings-updated', list); }
  };
  async function poll() {
    try { for (const item of await request('/api/events')) emit(item.type, item.data); }
    catch (error) { /* Retry when the local Java host is ready. */ }
    setTimeout(poll, 700);
  }
  poll();
})();
