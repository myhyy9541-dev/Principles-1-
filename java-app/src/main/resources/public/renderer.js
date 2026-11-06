const $ = id => document.getElementById(id);
const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
const MAX_FILE_BYTES = 1024 * 1024 * 1024, UPLOAD_CHUNK_BYTES = 1024 * 1024;
const HISTORY_PAGE_SIZE = 200, IMAGE_PREVIEW_MAX_BYTES = 8 * 1024 * 1024;
let myName = localStorage.getItem('wasel-name') || 'مستخدم';
let clientId = localStorage.getItem('wasel-client-id');
if (!clientId) { clientId = `w${crypto.randomUUID().replaceAll('-', '')}`; localStorage.setItem('wasel-client-id', clientId); }
let connected = false, connecting = false, hostMode = false, currentHost = null;
let pendingFile = null, localStream = null, callMode = null, currentMembers = [], socket = null;
let connectionMode = localStorage.getItem('wasel-connection-mode') || 'direct', p2pActive = false, localPeerSocket = null, selfFileAddress = '127.0.0.1';
const peerSockets = new Map(), discoveredPeers = new Map(), signalSockets = new Map();
if (!['direct', 'host'].includes(connectionMode)) connectionMode = 'direct';
let appSettings = { networkInterface: 'auto', discoveryIntervalMs: 1800, autoConnect: true, openAtLogin: false, theme: 'light', defaultSaveDirectory: '', soundNotifications: true, sharePrinters: false, sharedPrinterIds: [] };
let discoveredRoom = null, networkSettingsView = null;
let printerView = null;
let contacts = [], groups = [], currentChat = 'general', allMessages = (() => { try { const saved = JSON.parse(localStorage.getItem('wasel-p2p-history') || '[]'); return Array.isArray(saved) ? saved.slice(-500) : []; } catch (_) { return []; } })(), searchTerm = '';
const messageById = new Map(allMessages.filter(message => message?.id).map(message => [message.id, message]));
const messageSockets = new Map(), historyCursors = new Map(), requestedChatHistory = new WeakMap();
let historyViewEnd = null, olderHistoryRequest = null, historyCacheTimer = null;
let acceptingCall = false, startingCall = false, callTransport = null, callPeerId = null, callOwnerId = null;
const peers = new Map();
const remoteMediaElements = new Map();
const deferredDescriptions = new Map();
const deferredCandidates = new Map();

function toast(message) {
  const element = $('toast');
  element.textContent = message;
  element.classList.remove('hidden');
  setTimeout(() => element.classList.add('hidden'), 3500);
}
let notificationAudioContext = null;
function prepareNotificationAudio() {
  try {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) return;
    notificationAudioContext ||= new AudioContextClass();
    if (notificationAudioContext.state === 'suspended') notificationAudioContext.resume().catch(() => {});
  } catch (_) { }
}
document.addEventListener('pointerdown', prepareNotificationAudio);
function playNotification(kind) {
  if (appSettings.soundNotifications === false || !notificationAudioContext || notificationAudioContext.state !== 'running') return;
  const context = notificationAudioContext, now = context.currentTime;
  const tones = kind === 'call' ? [660, 880] : kind === 'join' ? [660] : kind === 'leave' ? [420] : [760];
  tones.forEach((frequency, index) => {
    const oscillator = context.createOscillator(), gain = context.createGain(), start = now + index * 0.11;
    oscillator.type = 'sine'; oscillator.frequency.value = frequency; gain.gain.setValueAtTime(0.0001, start);
    gain.gain.exponentialRampToValueAtTime(0.035, start + 0.02); gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.16);
    oscillator.connect(gain); gain.connect(context.destination); oscillator.start(start); oscillator.stop(start + 0.17);
  });
}
function showDialog(title, message, { value = '', multiline = false, yesNo = false, confirmText = 'موافق' } = {}) {
  return new Promise(resolve => {
    const dialog = document.createElement('dialog'); dialog.className = 'wasel-prompt-dialog';
    const panel = document.createElement('div'); panel.className = 'wasel-prompt-panel';
    const heading = document.createElement('h2'); heading.textContent = title;
    const description = document.createElement('p'); description.textContent = message;
    panel.append(heading, description);
    let field = null;
    if (!yesNo) {
      field = document.createElement(multiline ? 'textarea' : 'input'); field.value = value; field.maxLength = 5000; field.placeholder = multiline ? 'اكتب رسالتك هنا…' : '';
      field.className = 'wasel-prompt-field'; panel.append(field);
    }
    const actions = document.createElement('div'); actions.className = 'wasel-prompt-actions';
    const cancel = document.createElement('button'); cancel.type = 'button'; cancel.className = 'call-control'; cancel.textContent = yesNo ? 'رفض' : 'إلغاء';
    const accept = document.createElement('button'); accept.type = 'button'; accept.className = 'icon-btn primary'; accept.textContent = yesNo ? 'قبول' : confirmText;
    actions.append(cancel, accept); panel.append(actions); dialog.append(panel); document.body.append(dialog);
    let settled = false;
    const finish = result => { if (settled) return; settled = true; if (dialog.open) dialog.close(); dialog.remove(); resolve(result); };
    cancel.onclick = () => finish(yesNo ? false : null);
    accept.onclick = () => finish(yesNo ? true : (field.value.trim() || null));
    dialog.addEventListener('cancel', event => { event.preventDefault(); finish(yesNo ? false : null); }, { once: true });
    dialog.addEventListener('keydown', event => { if (!multiline && !yesNo && event.key === 'Enter') { event.preventDefault(); accept.click(); } });
    dialog.showModal(); field?.focus();
  });
}
function formatTime(value) { return new Date(value || Date.now()).toLocaleTimeString('ar', { hour: '2-digit', minute: '2-digit' }); }
function fileUrl(id, message = {}) {
  if (!/^[0-9a-f-]{36}$/i.test(String(id))) return '#';
  const address = message.fileHost || currentHost?.address;
  const port = message.filePort || currentHost?.filePort || 43822;
  return address ? `http://${address}:${port}/api/files/${encodeURIComponent(id)}` : '#';
}
function addMessage(message, target = $('messages')) {
  if (target === $('messages')) target.querySelector('.welcome')?.remove();
  if (message.type === 'system') {
    const line = document.createElement('div');
    line.className = 'system-message';
    line.textContent = message.text;
    target.append(line);
    return;
  }
  const mine = message.senderClientId ? message.senderClientId === clientId : message.senderId === window._socketId;
  const row = document.createElement('div');
  row.className = `message${mine ? ' mine' : ''}`;
  const initial = escapeHtml((message.user || '?').slice(0, 1));
  let content = escapeHtml(message.text || '');
  if (message.type === 'file') {
    const url = fileUrl(message.id, message);
    const mime = String(message.mime || '').toLowerCase();
    const preview = Number(message.size || 0) <= IMAGE_PREVIEW_MAX_BYTES && /^image\/(png|jpeg|gif|webp|avif)$/.test(mime)
      ? `<img class="image-preview" src="${url}?preview=1" alt="${escapeHtml(message.name)}" loading="lazy">` : '';
    const player = mime.startsWith('audio/') ? `<audio class="inline-audio" controls preload="none" src="${url}?preview=1"></audio>`
      : mime.startsWith('video/') ? `<video class="inline-video" controls preload="metadata" src="${url}?preview=1"></video>` : '';
    const printAction = mine ? `<button type="button" class="preview-button print-action" data-print-id="${escapeHtml(message.id)}">طباعة عبر مستخدم</button>` : '';
    content = `<div class="file-card"><a class="file-download" download="${escapeHtml(message.name)}" href="${url}"><span class="file-icon">▤</span><span class="file-label">${escapeHtml(message.name)}<small>ملف مشترك · ${escapeHtml(message.user)} · ${formatSize(message.size)}</small></span><span>تنزيل</span></a><button type="button" class="preview-button" data-preview-id="${escapeHtml(message.id)}">معاينة</button>${printAction}</div>${preview}${player}`;
  }
  const status = mine && message.channel !== 'general' ? `<span class="delivery-state">${(message.readBy || []).length ? '✓✓ تمت القراءة' : (message.deliveredTo || []).length ? '✓ تم التسليم' : '◷ قيد التسليم'}</span>` : '';
  row.innerHTML = `<div class="msg-avatar">${initial}</div><div class="bubble-wrap"><div class="msg-meta"><b>${escapeHtml(message.user || 'النظام')}</b><span class="msg-time">${formatTime(message.time)}</span>${status}</div><div class="bubble">${content}</div></div>`;
  row.dataset.messageId = message.id || '';
  target.append(row);
}
function showFilePreview(message) {
  const dialog = $('fileViewer'), body = $('fileViewerBody'), mime = String(message.mime || '').toLowerCase();
  const url = `${fileUrl(message.id, message)}?preview=1`;
  $('fileViewerTitle').textContent = message.name || 'معاينة الملف'; body.replaceChildren();
  if (/\.(docx?|xlsx?|pptx?)$/i.test(message.name || '')) {
    const frame = document.createElement('iframe'); frame.className = 'viewer-frame'; frame.title = `معاينة ${message.name || 'مستند Office'}`; frame.sandbox = 'allow-same-origin'; frame.src = url; body.append(frame);
  } else if (/^image\/(png|jpeg|gif|webp|avif)$/.test(mime)) {
    if (Number(message.size || 0) > IMAGE_PREVIEW_MAX_BYTES) body.textContent = 'الصورة أكبر من الحد المسموح للمعاينة؛ نزّلها لعرضها.';
    else { const image = document.createElement('img'); image.className = 'viewer-image'; image.src = url; image.alt = message.name || ''; image.onerror = () => { body.textContent = 'تعذرت معاينة الصورة؛ نزّل الملف للتحقق منه.'; }; body.append(image); }
  } else if (/^(audio|video)\//.test(mime)) {
    const media = document.createElement(mime.startsWith('audio/') ? 'audio' : 'video'); media.controls = true; media.autoplay = false; media.src = url; media.className = 'viewer-media'; body.append(media);
  } else if (mime === 'application/pdf' || mime === 'text/plain') {
    const frame = document.createElement('iframe'); frame.className = 'viewer-frame'; frame.title = `معاينة ${message.name || 'الملف'}`; frame.src = url; body.append(frame);
  } else {
    body.textContent = 'لا تتوفر معاينة داخلية لهذا النوع؛ يمكنك تنزيل الملف وفتحه بالتطبيق المناسب على Windows.';
  }
  dialog.showModal();
}
$('messages').addEventListener('click', event => {
  const printButton = event.target.closest('[data-print-id]');
  if (printButton) { const message = messageById.get(printButton.dataset.printId); if (message?.type === 'file') requestRemotePrint(message).catch(error => toast(error.message)); return; }
  const thumbnail = event.target.closest('.image-preview');
  if (thumbnail) {
    const message = messageById.get(thumbnail.closest('.message')?.dataset.messageId);
    if (message?.type === 'file') showFilePreview(message);
    return;
  }
  const button = event.target.closest('[data-preview-id]');
  if (!button) return;
  const message = messageById.get(button.dataset.previewId);
  if (message?.type === 'file') showFilePreview(message);
});
$('closeFileViewer').onclick = () => $('fileViewer').close();
function messageBelongs(message) {
  if (message.type === 'system') return false;
  if (currentChat === 'general') return message.channel === 'general' || message.channel === 'broadcast';
  if (currentChat.startsWith('user:')) {
    const id = currentChat.slice(5);
    return message.channel === 'direct' && ((message.senderClientId === clientId && message.toId === id) || (message.senderClientId === id && message.toId === clientId));
  }
  return currentChat.startsWith('group:') && message.groupId === currentChat.slice(6);
}
function persistHistoryCache() {
  if (connectionMode !== 'direct' || historyCacheTimer) return;
  historyCacheTimer = setTimeout(() => {
    historyCacheTimer = null;
    try { localStorage.setItem('wasel-p2p-history', JSON.stringify(allMessages.slice(-500))); } catch (_) { }
  }, 800);
}
function mergeMessages(messages) {
  const fresh = [];
  for (const message of messages || []) {
    if (!message?.id) continue;
    const old = messageById.get(message.id);
    if (old) Object.assign(old, message);
    else { messageById.set(message.id, message); fresh.push(message); }
  }
  const compare = (a, b) => (a.time || 0) - (b.time || 0) || String(a.id).localeCompare(String(b.id));
  if (fresh.length) {
    fresh.sort(compare);
    if (!allMessages.length || compare(allMessages[allMessages.length - 1], fresh[0]) <= 0) allMessages.push(...fresh);
    else if (compare(fresh[fresh.length - 1], allMessages[0]) < 0) allMessages = fresh.concat(allMessages);
    else {
      const merged = []; let i = 0, j = 0;
      while (i < allMessages.length && j < fresh.length) merged.push(compare(allMessages[i], fresh[j]) <= 0 ? allMessages[i++] : fresh[j++]);
      allMessages = merged.concat(allMessages.slice(i), fresh.slice(j));
    }
  }
  persistHistoryCache();
  return fresh.length;
}
function visibleMessages(chat = currentChat) {
  const query = searchTerm.toLocaleLowerCase();
  return allMessages.filter(message => messageBelongsFor(message, chat) && (!query || String(message.text || message.name || '').toLocaleLowerCase().includes(query)));
}
function messageBelongsFor(message, chat) {
  if (message.type === 'system') return false;
  if (chat === 'general') return message.channel === 'general' || message.channel === 'broadcast';
  if (chat.startsWith('user:')) {
    const id = chat.slice(5);
    return message.channel === 'direct' && ((message.senderClientId === clientId && message.toId === id) || (message.senderClientId === id && message.toId === clientId));
  }
  return chat.startsWith('group:') && message.groupId === chat.slice(6);
}
function historyCursor(source, chat) {
  let byChat = historyCursors.get(source);
  if (!byChat) { byChat = new Map(); historyCursors.set(source, byChat); }
  let state = byChat.get(chat);
  if (!state) { state = { beforeTime: Number.MAX_SAFE_INTEGER, beforeId: '\uffff', hasMore: true, loading: false }; byChat.set(chat, state); }
  return state;
}
function setHistoryCursor(source, chat, messages) {
  const cursor = historyCursor(source, chat);
  const first = messages?.[0];
  cursor.loading = false;
  cursor.hasMore = (messages || []).length >= HISTORY_PAGE_SIZE;
  if (first) { cursor.beforeTime = Number(first.time) || 0; cursor.beforeId = String(first.id || ''); }
  else cursor.hasMore = false;
}
function routeHistoryMessages(messages, source) {
  const owner = [...peerSockets.values()].find(item => item.socket === source)?.info;
  return (messages || []).map(message => {
    if (message.id) messageSockets.set(message.id, source);
    return message.type === 'file' ? { ...message, fileHost: owner?.address || (source === localPeerSocket ? '127.0.0.1' : currentHost?.address || '127.0.0.1'), filePort: owner?.filePort || currentHost?.filePort || 43822 } : message;
  });
}
function historySources() {
  if (connectionMode !== 'direct') return socket?.connected ? [socket] : [];
  const sources = [];
  if (localPeerSocket?.connected) sources.push(localPeerSocket);
  for (const item of peerSockets.values()) if (item.socket?.connected) sources.push(item.socket);
  return [...new Set(sources)];
}
function requestChatHistory(chat) {
  for (const source of historySources()) requestSourceChatHistory(source, chat);
}
function requestSourceChatHistory(source, chat) {
  let requested = requestedChatHistory.get(source);
  if (!requested) { requested = new Set(); requestedChatHistory.set(source, requested); }
  if (requested.has(chat)) return;
  requested.add(chat); source.emit('history-chat', { chat });
}
function requestOlderHistory() {
  if (olderHistoryRequest) return;
  const sources = historySources().filter(source => {
    const cursor = historyCursor(source, currentChat);
    return cursor.hasMore && !cursor.loading;
  });
  if (!sources.length) { toast('تم الوصول إلى أقدم الرسائل المحمّلة حالياً.'); return; }
  const beforeCount = visibleMessages().length;
  const request = olderHistoryRequest = { chat: currentChat, pending: new Set(sources), beforeCount, added: 0 };
  for (const source of sources) {
    const cursor = historyCursor(source, currentChat); cursor.loading = true;
    source.emit('history-before', { chat: currentChat, beforeTime: cursor.beforeTime, beforeId: cursor.beforeId });
  }
  setTimeout(() => {
    if (olderHistoryRequest !== request) return;
    for (const source of request.pending) historyCursor(source, request.chat).loading = false;
    request.pending.clear();
    finishOlderHistory();
  }, 10000);
}
function finishOlderHistory() {
  if (!olderHistoryRequest || olderHistoryRequest.pending.size) return;
  const request = olderHistoryRequest; olderHistoryRequest = null;
  if (request.chat !== currentChat) return;
  const nowCount = visibleMessages().length;
  historyViewEnd = request.beforeCount > 0 ? request.beforeCount : Math.min(nowCount, HISTORY_PAGE_SIZE);
  renderMessages();
}
function renderMessages() {
  const box = $('messages'); box.innerHTML = '';
  const list = visibleMessages();
  if (!list.length) {
    box.innerHTML = '<div class="welcome"><div class="welcome-icon">✦</div><h2>لا توجد رسائل هنا بعد</h2><p>ابدأ المحادثة مع فريقك على الشبكة المحلية.</p></div>';
    $('searchCount').textContent = searchTerm ? '0 نتيجة' : '';
    return;
  }
  const end = Math.min(historyViewEnd == null ? list.length : historyViewEnd, list.length);
  const start = Math.max(0, end - HISTORY_PAGE_SIZE);
  if (start > 0 || historyCursorHasMore()) {
    const older = document.createElement('button'); older.className = 'history-page-control';
    older.textContent = start > 0 ? 'عرض الرسائل الأقدم المحمّلة' : (olderHistoryRequest ? 'جارٍ تحميل الرسائل الأقدم…' : 'تحميل رسائل أقدم');
    older.disabled = Boolean(olderHistoryRequest);
    older.onclick = () => { if (start > 0) { historyViewEnd = start; renderMessages(); } else requestOlderHistory(); };
    box.append(older);
  }
  const fragment = document.createDocumentFragment();
  for (const message of list.slice(start, end)) addMessage(message, fragment);
  box.append(fragment);
  if (end < list.length) {
    const newer = document.createElement('button'); newer.className = 'history-page-control'; newer.textContent = 'عرض الرسائل الأحدث';
    newer.onclick = () => { const next = Math.min(list.length, end + HISTORY_PAGE_SIZE); historyViewEnd = next >= list.length ? null : next; renderMessages(); };
    box.append(newer);
  }
  $('searchCount').textContent = searchTerm ? `${list.length} نتيجة` : '';
  box.scrollTop = historyViewEnd == null ? box.scrollHeight : box.scrollHeight;
  markVisibleRead(list.slice(start, end));
}
function historyCursorHasMore() {
  return historySources().some(source => historyCursor(source, currentChat).hasMore);
}
function markVisibleRead(messages) {
  const pending = new Map();
  for (const message of messages) {
    if (message.senderClientId === clientId || message.channel === 'general' || message.channel === 'broadcast' || (message.readBy || []).includes(clientId)) continue;
    const source = messageSockets.get(message.id) || (connectionMode === 'direct' ? null : socket);
    if (!source?.connected) continue;
    message.readBy ||= []; message.readBy.push(clientId);
    if (!pending.has(source)) pending.set(source, []);
    pending.get(source).push(message.id);
  }
  for (const [source, ids] of pending) for (let i = 0; i < ids.length; i += 500) source.emit('mark-read', { ids: ids.slice(i, i + 500) });
}
function updateDeliveryBadge(message) {
  const mine = message.senderClientId ? message.senderClientId === clientId : message.senderId === window._socketId;
  if (!mine || message.channel === 'general') return;
  const row = [...$('messages').querySelectorAll('.message')].find(item => item.dataset.messageId === message.id);
  if (!row) return;
  let badge = row.querySelector('.delivery-state');
  if (!badge) {
    badge = document.createElement('span'); badge.className = 'delivery-state';
    row.querySelector('.msg-meta')?.append(badge);
  }
  badge.textContent = (message.readBy || []).length ? '✓✓ تمت القراءة'
    : (message.deliveredTo || []).length ? '✓ تم التسليم' : '◷ قيد التسليم';
}
function formatSize(bytes = 0) {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} غ.ب`;
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} ك.ب` : `${(bytes / 1024 / 1024).toFixed(1)} م.ب`;
}
function renderMembers(members) {
  if (connectionMode === 'direct') { renderPeerMembers(); return; }
  const nextIds = new Set(members.map(member => member.id));
  for (const id of peers.keys()) if (!nextIds.has(id)) removeCallPeer(id);
  currentMembers = members;
  $('memberCount').textContent = members.length;
  $('members').innerHTML = members.map(user => `<button class="member member-button" data-chat="user:${escapeHtml(user.clientId)}"><span class="avatar">${escapeHtml(user.name.slice(0, 1))}</span>${escapeHtml(user.name)}<span class="green-dot"></span></button>`).join('');
}
function refreshPeerContacts() {
  for (const peer of discoveredPeers.values()) {
    let item = contacts.find(contact => contact.clientId === peer.clientId);
    if (!item) { item = { clientId: peer.clientId, name: peer.name || 'مستخدم', online: false }; contacts.push(item); }
    item.name = peer.name || item.name || 'مستخدم';
    item.online = Boolean(peerSockets.get(peer.clientId)?.socket.connected);
  }
  renderChatOptions(); renderPeerMembers();
}
function renderPeerMembers() {
  const online = [...discoveredPeers.values()].filter(peer => peerSockets.get(peer.clientId)?.socket.connected);
  currentMembers = online.map(peer => ({ id: peer.remoteSessionId || peer.clientId, clientId: peer.clientId, name: peer.name || 'مستخدم' }));
  $('memberCount').textContent = online.length;
  $('members').innerHTML = [...discoveredPeers.values()].map(peer => `<button class="member member-button" data-chat="user:${escapeHtml(peer.clientId)}"><span class="avatar">${escapeHtml((peer.name || 'م').slice(0, 1))}</span>${escapeHtml(peer.name || 'مستخدم')}<span class="${peerSockets.get(peer.clientId)?.socket.connected ? 'green-dot' : 'offline-mark'}"></span></button>`).join('');
  document.querySelectorAll('#members [data-chat]').forEach(button => button.onclick = () => selectChat(button.dataset.chat));
}
function renderChatOptions() {
  const select = $('chatSelect');
  const selected = currentChat;
  select.innerHTML = '<option value="general">المحادثة العامة</option>' + contacts.filter(contact => contact.clientId !== clientId).map(contact => `<option value="user:${escapeHtml(contact.clientId)}">خاص: ${escapeHtml(contact.name)}${contact.online ? '' : ' (غير متصل)'}</option>`).join('') + groups.map(group => `<option value="group:${escapeHtml(group.id)}">مجموعة: ${escapeHtml(group.name)}</option>`).join('');
  if ([...select.options].some(option => option.value === selected)) select.value = selected;
  $('chatList').innerHTML = `<button class="room-item ${currentChat === 'general' ? 'active' : ''}" data-chat="general"><span class="hash">#</span><span>عام</span><span class="online-pill">محلي</span></button>` + contacts.filter(contact => contact.clientId !== clientId).map(contact => `<button class="room-item ${currentChat === `user:${contact.clientId}` ? 'active' : ''}" data-chat="user:${escapeHtml(contact.clientId)}"><span class="avatar">${escapeHtml(contact.name.slice(0,1))}</span><span>${escapeHtml(contact.name)}</span>${contact.online ? '<span class="green-dot"></span>' : '<span class="offline-mark">○</span>'}</button>`).join('') + groups.map(group => `<button class="room-item ${currentChat === `group:${group.id}` ? 'active' : ''}" data-chat="group:${escapeHtml(group.id)}"><span class="hash">#</span><span>${escapeHtml(group.name)}</span></button>`).join('');
  document.querySelectorAll('[data-chat]').forEach(button => button.onclick = () => selectChat(button.dataset.chat));
}
function selectChat(chat) {
  if (olderHistoryRequest) {
    for (const source of olderHistoryRequest.pending) historyCursor(source, olderHistoryRequest.chat).loading = false;
    olderHistoryRequest.pending.clear(); olderHistoryRequest = null;
  }
  currentChat = chat; $('chatSelect').value = chat;
  historyViewEnd = null;
  if (chat === 'general') $('chatTitle').textContent = 'المحادثة العامة';
  else if (chat.startsWith('user:')) $('chatTitle').textContent = `محادثة خاصة · ${contacts.find(item => item.clientId === chat.slice(5))?.name || 'مستخدم'}`;
  else $('chatTitle').textContent = `مجموعة · ${groups.find(item => item.id === chat.slice(6))?.name || 'مجموعة'}`;
  renderChatOptions(); renderMessages(); requestChatHistory(chat);
}
const recentSystemSounds = new Map();
function handleEvent(event, data, source = socket) {
  switch (event) {
    case 'connect':
      connected = true; connecting = false; window._socketId = data?.id;
      $('connectionText').textContent = connectionMode === 'direct' ? 'متصل مباشرة عبر P2P' : 'متصل بالشبكة المحلية'; $('roomTitle').textContent = connectionMode === 'direct' ? 'شبكة المنشأة المباشرة' : 'غرفة المنشأة';
      break;
    case 'disconnect':
      connected = false; connecting = true; $('connectionText').textContent = 'انقطع الاتصال؛ جارٍ إعادة المحاولة…';
      break;
    case 'connect_error':
      connected = false; $('connectionText').textContent = 'تعذر الاتصال؛ جارٍ إعادة المحاولة…';
      break;
    case 'identity': clientId = data.clientId; localStorage.setItem('wasel-client-id', clientId); break;
    case 'contacts': contacts = data || []; if (connectionMode === 'direct') refreshPeerContacts(); else renderChatOptions(); break;
    case 'groups': groups = data; renderChatOptions(); break;
    case 'history': {
      const history = routeHistoryMessages(data || [], source);
      setHistoryCursor(source, '__all__', history); mergeMessages(history); renderMessages(); break;
    }
    case 'history-chat-page': {
      const chat = data?.chat || 'general', history = routeHistoryMessages(data?.messages || [], source);
      setHistoryCursor(source, chat, history); mergeMessages(history);
      if (chat === currentChat) renderMessages();
      break;
    }
    case 'history-older': {
      const chat = data?.chat || currentChat, history = routeHistoryMessages(data?.messages || [], source);
      const cursor = historyCursor(source, chat);
      if (history.length) setHistoryCursor(source, chat, history); else { cursor.loading = false; cursor.hasMore = false; }
      const added = mergeMessages(history.filter(message => messageBelongsFor(message, chat)));
      if (olderHistoryRequest?.chat === chat && olderHistoryRequest.pending.has(source)) {
        olderHistoryRequest.pending.delete(source); olderHistoryRequest.added += added; finishOlderHistory();
      } else if (chat === currentChat) renderMessages();
      break;
    }
    case 'members': if (connectionMode !== 'direct' || source === localPeerSocket) renderMembers(data); break;
    case 'system-message': {
      addMessage({ type: 'system', text: data.text });
      if (/انضم|غادر/.test(data?.text || '')) {
        const key = data.text, now = Date.now(), previous = recentSystemSounds.get(key) || 0;
        if (now - previous > 1500) playNotification(/انضم/.test(data.text) ? 'join' : 'leave');
        recentSystemSounds.set(key, now);
      }
      break;
    }
    case 'chat-message': {
      if (data?.id) messageSockets.set(data.id, source);
      mergeMessages([data]);
      if (data?.senderClientId !== clientId) playNotification('message');
      if (messageBelongsFor(data, currentChat) && historyViewEnd == null) renderMessages();
      break;
    }
    case 'file-message': {
      if (data?.id) messageSockets.set(data.id, source);
      const fromPeer = [...peerSockets.values()].find(item => item.socket === source)?.info;
      const file = { type: 'file', ...data };
      if (data?.senderClientId !== clientId) playNotification('message');
      if (connectionMode === 'direct') { file.fileHost = fromPeer?.address || (source === localPeerSocket ? '127.0.0.1' : '127.0.0.1'); file.filePort = fromPeer?.filePort || currentHost?.filePort || 43822; }
      mergeMessages([file]);
      if (messageBelongsFor(file, currentChat) && historyViewEnd == null) renderMessages();
      break;
    }
    case 'message-receipt': {
      const ids = Array.isArray(data.ids) ? data.ids : [data.id];
      for (const id of ids) {
        const message = messageById.get(id); if (!message) continue;
        const key = data.kind === 'read' ? 'readBy' : 'deliveredTo'; message[key] ||= [];
        if (!message[key].includes(data.clientId)) { message[key].push(data.clientId); updateDeliveryBadge(message); }
      }
      break;
    }
    case 'notice': toast(data); break;
    case 'print-list-request': publishPrinterList(data, source || socket); break;
    case 'print-printers': pendingPrintListRequests.get(data?.requestId)?.(data.printers || []); break;
    case 'print-offer': receivePrintOffer(data, source || socket); break;
    case 'print-result': toast(data?.ok ? `أُرسلت الطباعة: ${data.status || 'إلى Windows'}` : `تعذرت الطباعة: ${data?.status || 'رفض الطلب'}`); break;
    case 'signal': receiveSignal(data, source).catch(error => toast(`تعذر إعداد الاتصال: ${error.message}`)); break;
  }
}
function connect(address, port, filePort = 43822) {
  if ((connected || connecting) && currentHost?.address === address && currentHost?.port === port) return;
  socket?.disconnect(); socket = null; connected = false; connecting = false;
  connecting = true;
  currentHost = { address, port, filePort };
  $('connectionText').textContent = `الاتصال بـ ${address}…`;
  if (!window.io) { connecting = false; return toast('ملف الاتصال المحلي غير متوفر؛ أعد تثبيت التطبيق.'); }
  socket?.disconnect();
  socket = window.io(`http://${address}:${port}`, { transports: ['websocket', 'polling'], timeout: 5000, reconnection: true });
  for (const event of ['connect', 'connect_error', 'disconnect', 'identity', 'history', 'history-chat-page', 'history-older', 'members', 'contacts', 'groups', 'system-message', 'chat-message', 'file-message', 'message-receipt', 'signal', 'notice', 'print-list-request', 'print-printers', 'print-offer', 'print-result']) {
    socket.on(event, data => {
      if (event === 'connect') { socket.emit('join', { name: myName, clientId }); handleEvent(event, { id: socket.id }); requestSourceChatHistory(socket, currentChat); }
      else handleEvent(event, data);
    });
  }
}
function bindPeerSocket(peerSocket, peer = null, local = false) {
  peerSocket.on('connect', () => {
    peerSocket.emit('join', { name: myName, clientId });
    requestSourceChatHistory(peerSocket, currentChat);
    if (local) handleEvent('connect', { id: peerSocket.id }, peerSocket);
    else refreshPeerContacts();
  });
  peerSocket.on('disconnect', () => {
    if (local) handleEvent('disconnect', {}, peerSocket);
    else refreshPeerContacts();
  });
  peerSocket.on('connect_error', error => {
    if (local) handleEvent('connect_error', error, peerSocket);
    else refreshPeerContacts();
  });
  for (const event of ['identity', 'history', 'history-chat-page', 'history-older', 'members', 'contacts', 'groups', 'system-message', 'chat-message', 'file-message', 'message-receipt', 'signal', 'notice', 'print-list-request', 'print-printers', 'print-offer', 'print-result']) {
    peerSocket.on(event, data => {
      if (local) { handleEvent(event, data, peerSocket); return; }
      if (event === 'members') {
        const ownMember = (data || []).find(member => member.clientId === peer.clientId);
        if (ownMember) {
          peer.remoteSessionId = ownMember.id;
          const connection = peerSockets.get(peer.clientId);
          if (connection?.socket === peerSocket) connection.remoteSessionId = ownMember.id;
        }
        refreshPeerMembers();
      } else if (['history', 'history-chat-page', 'history-older', 'chat-message', 'file-message', 'message-receipt', 'signal', 'notice', 'print-list-request', 'print-printers', 'print-offer', 'print-result'].includes(event)) {
        handleEvent(event, data, peerSocket);
      }
    });
  }
}
function connectPeer(peer) {
  if (!p2pActive || connectionMode !== 'direct' || !peer?.clientId || peer.clientId === clientId) return;
  const previous = peerSockets.get(peer.clientId);
  if (previous?.socket.connected) return;
  if (previous && previous.info.address === peer.address && previous.socket.active) return;
  if (previous && Date.now() - (previous.attemptedAt || 0) < 5000) return;
  if (previous) { previous.socket.disconnect(); peerSockets.delete(peer.clientId); }
  const peerSocket = window.io(`http://${peer.address}:${peer.port || 43823}`, { transports: ['websocket', 'polling'], timeout: 5000, reconnection: true });
  peerSockets.set(peer.clientId, { socket: peerSocket, info: peer, remoteSessionId: null, attemptedAt: Date.now() });
  bindPeerSocket(peerSocket, peer, false);
}
async function startPeerMode() {
  if (connectionMode !== 'direct' || !myName.trim()) return;
  try {
    const info = await window.wasel.lanInfo();
    selfFileAddress = info.addresses?.[0] || '127.0.0.1';
    await window.wasel.startPeer(clientId, myName);
    p2pActive = true; currentHost = null;
    if (socket && socket !== localPeerSocket) { socket.disconnect(); socket = null; }
    if (!localPeerSocket) {
      localPeerSocket = window.io('http://127.0.0.1:43823', { transports: ['websocket', 'polling'], timeout: 5000, reconnection: true });
      socket = localPeerSocket; bindPeerSocket(localPeerSocket, null, true);
    } else {
      socket = localPeerSocket;
      if (!localPeerSocket.connected) localPeerSocket.connect();
      else localPeerSocket.emit('update-profile', { name: myName });
    }
    for (const peer of discoveredPeers.values()) connectPeer(peer);
    $('connectionModeSelect').value = 'direct';
    $('createRoom').textContent = '＋ إنشاء غرفة مؤقتة';
    $('connectionText').textContent = 'يتم اكتشاف أجهزة P2P تلقائياً…';
  } catch (error) { p2pActive = false; toast(`تعذر بدء P2P: ${error.message}`); }
}
async function stopPeerMode() {
  p2pActive = false;
  for (const item of peerSockets.values()) item.socket.disconnect();
  peerSockets.clear();
  if (localPeerSocket) { const old = localPeerSocket; old.disconnect(); if (socket === old) socket = null; localPeerSocket = null; }
  try { await window.wasel.stopPeer(); } catch (_) { }
}
async function setConnectionMode(mode) {
  if (!['direct', 'host'].includes(mode) || mode === connectionMode) return;
  connectionMode = mode; localStorage.setItem('wasel-connection-mode', mode);
  $('connectionModeSelect').value = mode; closeCall(); currentChat = 'general';
  if (mode === 'direct') {
    if (socket && socket !== localPeerSocket) { socket.disconnect(); socket = null; }
    connected = false; connecting = false; currentHost = null;
    await startPeerMode();
  } else {
    await stopPeerMode(); connected = false; connecting = false; currentHost = null;
    $('createRoom').textContent = '＋ إنشاء غرفة جديدة';
    $('connectionText').textContent = 'بانتظار مضيف مؤقت على الشبكة…';
    if (hostMode) connect('127.0.0.1', 43821, 43822);
    else if (discoveredRoom && appSettings.autoConnect) connect(discoveredRoom.address, discoveredRoom.port, discoveredRoom.filePort);
  }
  renderChatOptions(); renderMessages();
}
window.wasel.onRoomFound(room => {
  discoveredRoom = room;
  $('detectedRoomText').textContent = `تم العثور على مضيف «${room.name || 'غرفة محلية'}» · ${room.address}:${room.port}`;
  $('connectDetectedBtn').disabled = false;
  if (connectionMode === 'host' && appSettings.autoConnect && !connected && !connecting && !hostMode) connect(room.address, room.port, room.filePort);
});
window.wasel.onPeerFound(peer => {
  if (!peer.clientId || peer.clientId === clientId) return;
  const old = discoveredPeers.get(peer.clientId);
  const connection = peerSockets.get(peer.clientId);
  if (old && old.address !== peer.address && connection?.socket.connected) peer.address = connection.info.address;
  peer.lastSeen = Date.now(); discoveredPeers.set(peer.clientId, peer);
  if (connectionMode === 'direct' && p2pActive) connectPeer(peer);
  if (connectionMode === 'direct') refreshPeerContacts();
});
setInterval(() => {
  const ttl = Math.max(15000, (appSettings.discoveryIntervalMs || 1800) * 3);
  let changed = false;
  for (const [id, peer] of discoveredPeers) if (Date.now() - (peer.lastSeen || 0) > ttl) {
    const item = peerSockets.get(id);
    if (item?.socket.connected) { peer.lastSeen = Date.now(); continue; }
    item?.socket.disconnect(); peerSockets.delete(id); discoveredPeers.delete(id); changed = true;
    if (callPeerId === item?.remoteSessionId) closeCall(false);
  }
  if (changed && connectionMode === 'direct') refreshPeerContacts();
}, 5000);
window.wasel.onRoomStarted(data => {
  hostMode = true;
  $('roomBanner').classList.remove('hidden');
  $('hostAddress').textContent = `العنوان: ${data.addresses.join(' · ')} — المنفذ ${data.port}`;
  connect('127.0.0.1', data.port, data.filePort);
});
window.wasel.onNotice(message => toast(message));
$('createRoom').onclick = async () => {
  if (connectionMode !== 'host') await setConnectionMode('host');
  const button = $('createRoom');
  socket?.disconnect(); socket = null; connected = false; connecting = false; hostMode = true;
  button.disabled = true; button.textContent = 'جارٍ إنشاء الغرفة…';
  try {
    const result = await window.wasel.startRoom();
    if (!result.ok) { hostMode = false; toast(result.error || 'تعذر إنشاء الغرفة'); button.disabled = false; button.textContent = '＋ إنشاء غرفة جديدة'; return; }
    button.textContent = 'الغرفة تعمل على هذا الجهاز';
    toast('تم إنشاء الغرفة؛ تأكد من اتصال بقية الأجهزة بالشبكة نفسها.');
  } catch (error) {
    hostMode = false; toast(`تعذر إنشاء الغرفة: ${error.message}`); button.disabled = false; button.textContent = '＋ إنشاء غرفة جديدة';
  }
};
function sendText() {
  const input = $('messageInput');
  const text = input.value.trim();
  if (!text) return;
  if (!connected) return toast('انتظر اتصال الجهاز بالمضيف');
  const target = selectedTarget();
  let transport = socket;
  if (connectionMode === 'direct' && target.toId) {
    const targetSocket = peerSockets.get(target.toId)?.socket;
    transport = targetSocket?.connected ? targetSocket : localPeerSocket;
  }
  if (!transport?.connected) return toast('تعذر الاتصال بالطرف؛ انتظر إعادة اكتشافه.');
  transport.emit('chat', { text, ...target });
  input.value = ''; input.style.height = 'auto';
}
function selectedTarget() {
  if (currentChat === 'general') return { channel: 'general' };
  if (currentChat.startsWith('user:')) return { channel: 'direct', toId: currentChat.slice(5) };
  return { channel: 'direct', groupId: currentChat.slice(6) };
}
$('sendBtn').onclick = sendText;
$('messageInput').addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); sendText(); }
});
$('messageInput').addEventListener('input', event => {
  event.target.style.height = 'auto'; event.target.style.height = `${Math.min(event.target.scrollHeight, 110)}px`;
});
const transfers = new Map();
let activeUploads = 0, transferRenderTimer = null;
function scheduleTransferRender() { if (transferRenderTimer) return; transferRenderTimer = setTimeout(() => { transferRenderTimer = null; renderTransfers(); }, 120); }
$('attachBtn').onclick = () => $('fileInput').click();
$('folderBtn').onclick = () => $('folderInput').click();
$('fileInput').onchange = event => { queueFiles([...event.target.files]); event.target.value = ''; };
$('folderInput').onchange = event => { queueFiles([...event.target.files], true); event.target.value = ''; };
function queueFiles(files, folder = false, targetOverride = null) {
  if (!files.length) return;
  const target = targetOverride || selectedTarget();
  if (connectionMode === 'direct' && !target.toId) return toast('في وضع P2P اختر محادثة شخص محدد قبل إرسال ملف أو صورة.');
  for (const file of files) {
    if (!file.size) { toast(`تم تجاهل الملف الفارغ: ${file.name}`); continue; }
    if (file.size > MAX_FILE_BYTES) { toast(`تم تجاهل الملف؛ الحد الأقصى 1 غيغابايت: ${file.name}`); continue; }
    const name = folder && file.webkitRelativePath ? file.webkitRelativePath : file.name;
    const job = { id: crypto.randomUUID(), file, name, target, offset: 0, paused: false, canceled: false, state: 'بالانتظار' };
    transfers.set(job.id, job); renderTransfers(); runUpload(job);
  }
}
let recordingRequested = false, voiceRecorder = null, voiceRecorderStream = null, voiceRecorderChunks = [], voiceRecorderTarget = null;
async function beginVoiceRecording() {
  if (voiceRecorder || !recordingRequested) return;
  if (connectionMode === 'direct' && !currentChat.startsWith('user:')) { recordingRequested = false; return toast('اختر محادثة خاصة قبل تسجيل رسالة صوتية في وضع P2P.'); }
  if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) { recordingRequested = false; return toast('تسجيل الصوت غير مدعوم في إصدار Chromium الحالي.'); }
  voiceRecorderTarget = selectedTarget();
  try {
    voiceRecorderStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
    if (!recordingRequested) { voiceRecorderStream.getTracks().forEach(track => track.stop()); voiceRecorderStream = null; return; }
    const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus'];
    const mimeType = candidates.find(type => MediaRecorder.isTypeSupported(type)) || '';
    voiceRecorder = new MediaRecorder(voiceRecorderStream, mimeType ? { mimeType } : undefined);
    voiceRecorderChunks = [];
    voiceRecorder.ondataavailable = event => { if (event.data?.size) voiceRecorderChunks.push(event.data); };
    voiceRecorder.onerror = event => toast(`تعذر تسجيل الصوت: ${event.error?.message || 'خطأ في المسجل'}`);
    voiceRecorder.onstop = () => {
      const type = voiceRecorder?.mimeType || 'audio/webm';
      const extension = type.includes('ogg') ? 'ogg' : type.includes('mp4') ? 'm4a' : 'webm';
      const blob = new Blob(voiceRecorderChunks, { type });
      const file = new File([blob], `voice-note-${Date.now()}.${extension}`, { type });
      voiceRecorderStream?.getTracks().forEach(track => track.stop()); voiceRecorderStream = null; voiceRecorder = null;
      $('voiceRecordBtn').classList.remove('recording'); $('recordingHint').classList.add('hidden');
      if (file.size) queueFiles([file], false, voiceRecorderTarget); else toast('لم يلتقط التسجيل أي صوت.');
      voiceRecorderTarget = null; voiceRecorderChunks = [];
    };
    voiceRecorder.start(500); $('voiceRecordBtn').classList.add('recording'); $('recordingHint').classList.remove('hidden');
    if (!recordingRequested) stopVoiceRecording();
  } catch (error) {
    voiceRecorderStream?.getTracks().forEach(track => track.stop()); voiceRecorderStream = null; voiceRecorder = null; recordingRequested = false;
    toast(`تعذر استخدام الميكروفون: ${error.message}`);
  }
}
function stopVoiceRecording() {
  recordingRequested = false;
  if (voiceRecorder?.state === 'recording') voiceRecorder.stop();
  else if (!voiceRecorder) { voiceRecorderStream?.getTracks().forEach(track => track.stop()); voiceRecorderStream = null; }
}
$('voiceRecordBtn').addEventListener('pointerdown', event => { event.preventDefault(); recordingRequested = true; beginVoiceRecording(); });
window.addEventListener('pointerup', stopVoiceRecording);
window.addEventListener('pointercancel', stopVoiceRecording);
$('voiceRecordBtn').addEventListener('keydown', event => { if ((event.key === ' ' || event.key === 'Enter') && !event.repeat) { event.preventDefault(); recordingRequested = true; beginVoiceRecording(); } });
$('voiceRecordBtn').addEventListener('keyup', event => { if (event.key === ' ' || event.key === 'Enter') stopVoiceRecording(); });
let dragDepth = 0;
document.addEventListener('dragenter', event => { if (![...(event.dataTransfer?.types || [])].includes('Files')) return; event.preventDefault(); dragDepth++; $('dropOverlay').classList.remove('hidden'); });
document.addEventListener('dragover', event => { if ([...(event.dataTransfer?.types || [])].includes('Files')) event.preventDefault(); });
document.addEventListener('dragleave', event => { if (![...(event.dataTransfer?.types || [])].includes('Files')) return; dragDepth = Math.max(0, dragDepth - 1); if (!dragDepth) $('dropOverlay').classList.add('hidden'); });
document.addEventListener('drop', event => {
  if (![...(event.dataTransfer?.types || [])].includes('Files')) return;
  event.preventDefault(); dragDepth = 0; $('dropOverlay').classList.add('hidden'); queueFiles([...event.dataTransfer.files]);
});
function renderTransfers() {
  $('transferList').innerHTML = [...transfers.values()].map(job => {
    const percent = job.file.size ? Math.min(100, Math.floor(job.offset * 100 / job.file.size)) : 0;
    return `<div class="transfer-item"><div class="transfer-title"><b>${escapeHtml(job.name)}</b><span>${job.state} · ${percent}%</span><button data-pause="${job.id}" class="call-control">${job.paused ? 'استئناف' : 'إيقاف مؤقت'}</button><button data-cancel="${job.id}" class="call-control">إلغاء</button></div><progress max="100" value="${percent}"></progress></div>`;
  }).join('');
  document.querySelectorAll('[data-pause]').forEach(button => button.onclick = () => { const job = transfers.get(button.dataset.pause); if (job) { job.paused = !job.paused; job.state = job.paused ? 'متوقف مؤقتاً' : 'جارٍ الرفع'; if (!job.paused) { job.resume?.(); job.resume = null; runUpload(job); } renderTransfers(); } });
  document.querySelectorAll('[data-cancel]').forEach(button => button.onclick = () => { const job = transfers.get(button.dataset.cancel); if (job) { job.canceled = true; job.resume?.(); job.waitResolve?.(); job.resume = null; job.waitResolve = null; uploadSocket(job)?.emit('upload-cancel', { transferId: job.id }); transfers.delete(job.id); renderTransfers(); } });
}
function uploadSocket(job) {
  if (connectionMode === 'direct' && job?.target?.toId) {
    const peer = peerSockets.get(job.target.toId)?.socket;
    return peer?.connected ? peer : localPeerSocket;
  }
  return socket;
}
function socketAck(event, payload, transport = socket) {
  return new Promise((resolve, reject) => {
    if (!transport?.connected) return reject(new Error('انقطع الاتصال'));
    const timer = setTimeout(() => reject(new Error('انتهت مهلة الرفع')), 30000);
    transport.emit(event, payload, response => { clearTimeout(timer); if (response?.ok) resolve(response); else reject(new Error(response?.error || 'تعذر إكمال النقل')); });
  });
}
function encodeBase64(bytes) {
  let binary = '';
  for (let start = 0; start < bytes.length; start += 16 * 1024) binary += String.fromCharCode(...bytes.subarray(start, Math.min(start + 16 * 1024, bytes.length)));
  return btoa(binary);
}
async function waitConnected(job) {
  while (!job.canceled && !uploadSocket(job)?.connected) {
    job.state = 'بانتظار الشبكة'; renderTransfers();
    await new Promise(resolve => {
      let finished = false;
      const transport = uploadSocket(job);
      const finish = () => {
        if (finished) return;
        finished = true; clearTimeout(timer); transport?.off('connect', finish);
        if (job.waitResolve === finish) job.waitResolve = null;
        resolve();
      };
      const timer = setTimeout(finish, 3000);
      job.waitResolve = finish;
      if (transport?.connected) finish(); else transport?.once('connect', finish);
    });
  }
}
async function runUpload(job) {
  if (job.running) return;
  if (activeUploads >= 1) { job.state = 'في قائمة الانتظار'; renderTransfers(); return; }
  job.running = true; activeUploads++;
  try {
    while (!job.canceled && job.offset < job.file.size) {
      if (job.paused) { await new Promise(resolve => { job.resume = resolve; }); continue; }
      await waitConnected(job);
      if (job.canceled) break;
      job.state = 'جارٍ الرفع';
      const transport = uploadSocket(job);
      const init = await socketAck('upload-init', { transferId: job.id, name: job.name, mime: (job.file.type || 'application/octet-stream').split(';', 2)[0], size: job.file.size, ...job.target }, transport);
      job.offset = init.offset;
      while (!job.canceled && !job.paused && job.offset < job.file.size) {
        const end = Math.min(job.offset + UPLOAD_CHUNK_BYTES, job.file.size);
        const bytes = new Uint8Array(await job.file.slice(job.offset, end).arrayBuffer());
        const response = await socketAck('upload-chunk', { transferId: job.id, offset: job.offset, data: encodeBase64(bytes) }, transport);
        job.offset = response.offset; scheduleTransferRender();
      }
    }
    if (!job.canceled && job.offset === job.file.size) {
      await socketAck('upload-complete', { transferId: job.id }, uploadSocket(job)); job.state = 'اكتمل'; renderTransfers();
      setTimeout(() => { transfers.delete(job.id); renderTransfers(); }, 5000);
    }
  } catch (error) {
    if (!job.canceled) { job.state = 'تعذر النقل — اضغط استئناف'; job.paused = true; toast(error.message); }
  } finally {
    job.running = false; activeUploads = Math.max(0, activeUploads - 1); renderTransfers();
    const next = [...transfers.values()].find(item => !item.running && !item.paused && !item.canceled && item.state === 'في قائمة الانتظار');
    if (next) queueMicrotask(() => runUpload(next));
  }
}
$('chatSelect').onchange = event => selectChat(event.target.value);
$('newGroupBtn').onclick = async () => {
  if (connectionMode === 'direct') return toast('المجموعات تُدار حالياً من وضع المضيف المؤقت؛ المحادثة العامة وP2P الفردي متاحان هنا.');
  if (!connected) return toast('اتصل بالغرفة لإنشاء مجموعة.');
  const eligible = contacts.filter(contact => contact.clientId !== clientId);
  if (!eligible.length) return toast('لا يوجد مستخدمون آخرون معروفون بعد.');
  const choices = eligible.map((item, index) => `${index + 1}. ${item.name}${item.online ? '' : ' (غير متصل)'}`).join('\n');
  const name = await showDialog('إنشاء مجموعة', 'اكتب اسم المجموعة:'); if (!name?.trim()) return;
  const input = await showDialog('أعضاء المجموعة', `أدخل أرقام الأعضاء مفصولة بفواصل:\n${choices}`, { value: eligible.map((_, index) => index + 1).join(',') });
  if (!input) return;
  const members = input.split(',').map(value => eligible[Number(value.trim()) - 1]?.clientId).filter(Boolean);
  socket.emit('create-group', { name, members });
};
$('broadcastBtn').onclick = $('broadcastHere').onclick = async () => {
  const text = await showDialog('رسالة بث للجميع', 'اكتب الرسالة التي ستصل إلى المستخدمين المتصلين في الشبكة:', { multiline: true, confirmText: 'بث الرسالة' });
  if (text?.trim()) socket?.emit('broadcast', { text: text.trim() });
};
$('searchBtn').onclick = () => { $('searchBar').classList.toggle('hidden'); $('searchInput').focus(); };
$('searchInput').oninput = event => { searchTerm = event.target.value.trim(); renderMessages(); };
$('clearSearch').onclick = () => { $('searchInput').value = ''; searchTerm = ''; renderMessages(); };
$('exportBtn').onclick = () => {
  const rows = allMessages.filter(message => messageBelongs(message));
  const csv = ['"التاريخ","المرسل","النوع","الرسالة/الملف"', ...rows.map(message => [new Date(message.time).toISOString(), message.user || '', message.type || '', message.text || message.name || ''].map(value => `"${String(value).replaceAll('"', '""')}"`).join(','))].join('\r\n');
  const link = document.createElement('a'); link.href = URL.createObjectURL(new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' })); link.download = `wasel-history-${Date.now()}.csv`; link.click(); URL.revokeObjectURL(link.href);
};
function addVideo(label, stream, local = false, peerId = '') {
  if (!local && peerId && remoteMediaElements.has(peerId)) {
    const existing = remoteMediaElements.get(peerId); existing.srcObject = stream; existing.title = label; return existing;
  }
  const video = document.createElement('video');
  video.autoplay = true; video.playsInline = true; video.muted = local; video.srcObject = stream; video.title = label;
  if (local) video.className = 'local-video';
  if (peerId) { video.dataset.peer = peerId; remoteMediaElements.set(peerId, video); }
  $('videoGrid').append(video);
  return video;
}
function showCallPanel(mode) {
  callMode = mode;
  remoteMediaElements.clear();
  $('callTitle').textContent = mode === 'video' ? 'مكالمة فيديو' : 'مكالمة صوتية';
  $('callStatus').textContent = 'اتصال محلي مباشر';
  $('callPanel').classList.remove('hidden'); $('videoGrid').innerHTML = '';
  $('cameraBtn').classList.toggle('hidden', mode !== 'video');
  $('muteBtn').textContent = 'كتم الصوت'; $('cameraBtn').textContent = 'إيقاف الكاميرا';
  if (mode === 'video') addVideo('أنت', localStream, true);
}
function onlineCallTargets() {
  if (connectionMode === 'direct') return [...discoveredPeers.values()]
    .filter(peer => peer.clientId !== clientId && peerSockets.get(peer.clientId)?.socket.connected && peer.remoteSessionId)
    .map(peer => ({ clientId: peer.clientId, name: peer.name || 'مستخدم', id: peer.remoteSessionId }));
  return currentMembers.filter(user => user.clientId !== clientId).map(user => ({ clientId: user.clientId, name: user.name || 'مستخدم', id: user.id }));
}
function chooseCallRecipients(mode) {
  const targets = onlineCallTargets();
  if (!targets.length) { toast('لا يوجد مستخدمون متصلون يمكن دعوتهم الآن.'); return Promise.resolve(null); }
  return new Promise(resolve => {
    const dialog = document.createElement('dialog'); dialog.className = 'recipient-dialog';
    const form = document.createElement('form'); form.method = 'dialog';
    const heading = document.createElement('h2'); heading.textContent = mode === 'video' ? 'دعوة إلى مكالمة فيديو' : 'دعوة إلى مكالمة صوتية';
    const hint = document.createElement('p'); hint.textContent = connectionMode === 'host' ? 'اختر شخصاً واحداً أو أكثر لإرسال الدعوة.' : 'اختر مشاركاً واحداً للمكالمة المباشرة P2P.';
    const list = document.createElement('div'); list.className = 'recipient-list';
    for (const target of targets) {
      const label = document.createElement('label'); label.className = 'recipient-item';
      const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.value = target.clientId; checkbox.dataset.name = target.name;
      if (connectionMode === 'direct') checkbox.addEventListener('change', () => { if (checkbox.checked) list.querySelectorAll('input').forEach(other => { if (other !== checkbox) other.checked = false; }); });
      const name = document.createElement('span'); name.textContent = target.name;
      label.append(checkbox, name); list.append(label);
    }
    const actions = document.createElement('div'); actions.className = 'recipient-actions';
    const cancel = document.createElement('button'); cancel.type = 'button'; cancel.className = 'call-control'; cancel.textContent = 'إلغاء';
    const invite = document.createElement('button'); invite.type = 'button'; invite.className = 'icon-btn primary'; invite.textContent = 'إرسال الدعوة';
    actions.append(cancel, invite); form.append(heading, hint, list, actions); dialog.append(form); document.body.append(dialog);
    const finish = value => { if (dialog.open) dialog.close(); dialog.remove(); resolve(value); };
    cancel.onclick = () => finish(null);
    invite.onclick = () => {
      const selected = [...list.querySelectorAll('input:checked')].map(input => targets.find(target => target.clientId === input.value)).filter(Boolean);
      if (!selected.length) return toast('اختر مستخدماً واحداً على الأقل.');
      finish(selected);
    };
    dialog.addEventListener('cancel', event => { event.preventDefault(); finish(null); }, { once: true });
    dialog.showModal();
  });
}
async function startCall(mode, initiator = true, incomingTransport = null, selectedTargets = []) {
  if (callMode || startingCall) return toast('هناك مكالمة قائمة بالفعل. أنهها قبل بدء مكالمة أخرى.');
  if (!connected) return toast('اتصل بغرفة محلية أولاً');
  startingCall = true;
  try {
    let directTarget = null;
    if (connectionMode === 'direct' && initiator) {
      directTarget = peerSockets.get(selectedTargets[0]?.clientId || (currentChat.startsWith('user:') ? currentChat.slice(5) : ''));
      if (!directTarget?.socket.connected || !directTarget.remoteSessionId) return toast('الطرف لم يكتمل اتصاله بعد؛ انتظر ظهوره متصلاً ثم أعد المحاولة.');
    }
    localStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }, video: mode === 'video' ? { width: { ideal: 640, max: 1280 }, height: { ideal: 360, max: 720 }, frameRate: { ideal: 24, max: 30 } } : false });
    showCallPanel(mode);
    if (initiator) {
      callOwnerId = window._socketId || null;
      if (connectionMode === 'direct') {
        callTransport = directTarget.socket; callPeerId = directTarget.remoteSessionId; signalSockets.set(directTarget.remoteSessionId, callTransport);
        callTransport.emit('signal', { to: directTarget.remoteSessionId, targetClientId: directTarget.info.clientId, description: { type: 'call-start', mode } });
        makePeer(directTarget.remoteSessionId, true, callTransport);
      } else {
        callTransport = socket;
        socket?.emit('signal', { description: { type: 'call-start', mode }, targetClientIds: selectedTargets.map(target => target.clientId) });
      }
    } else {
      callTransport = incomingTransport || socket;
      if (connectionMode === 'direct') {
        for (const [id, packet] of deferredDescriptions) await processDescription(id, packet);
        deferredDescriptions.clear();
      } else {
        callTransport?.emit('signal', { description: { type: 'call-accept' } });
      }
    }
  } catch (error) {
    localStream?.getTracks().forEach(track => track.stop()); localStream = null;
    toast(`تعذر الوصول إلى الميكروفون/الكاميرا: ${error.message}`);
  } finally { startingCall = false; }
}
$('voiceBtn').onclick = async () => { const targets = await chooseCallRecipients('audio'); if (targets?.length) startCall('audio', true, null, targets); };
$('videoBtn').onclick = async () => { const targets = await chooseCallRecipients('video'); if (targets?.length) startCall('video', true, null, targets); };
$('muteBtn').onclick = () => {
  const tracks = localStream?.getAudioTracks() || [];
  if (!tracks.length) return;
  const enabled = !tracks[0].enabled; tracks.forEach(track => { track.enabled = enabled; });
  $('muteBtn').textContent = enabled ? 'كتم الصوت' : 'تشغيل الميكروفون';
};
$('cameraBtn').onclick = () => {
  const tracks = localStream?.getVideoTracks() || [];
  if (!tracks.length) return;
  const enabled = !tracks[0].enabled; tracks.forEach(track => { track.enabled = enabled; });
  $('cameraBtn').textContent = enabled ? 'إيقاف الكاميرا' : 'تشغيل الكاميرا';
};
function makePeer(id, offer, transport = null) {
  if (!localStream || id === window._socketId) return null;
  let pc = peers.get(id);
  if (pc) return pc;
  pc = new RTCPeerConnection({ iceServers: [] });
  peers.set(id, pc);
  const channel = transport || signalSockets.get(id) || socket;
  signalSockets.set(id, channel);
  localStream.getTracks().forEach(track => pc.addTrack(track, localStream));
  pc.onicecandidate = event => { if (event.candidate) channel?.emit('signal', { to: id, candidate: event.candidate }); };
  pc.ontrack = event => {
    const stream = event.streams[0] || new MediaStream([event.track]);
    if (!stream) return;
    if (callMode === 'video') {
      if (event.track.kind === 'video') addVideo('مشارك', stream, false, id);
    } else {
      let audio = document.querySelector(`audio[data-peer="${CSS.escape(String(id))}"]`);
      if (!audio) { audio = document.createElement('audio'); audio.autoplay = true; audio.dataset.peer = id; $('audioNodes').append(audio); }
      audio.srcObject = stream;
    }
  };
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === 'connected') $('callStatus').textContent = 'المكالمة متصلة';
    if (pc.connectionState === 'failed') toast('تعذر إنشاء اتصال مباشر؛ تحقق من السماح بحركة UDP بين الأجهزة.');
  };
  if (offer) pc.createOffer().then(description => pc.setLocalDescription(description)).then(() => {
    channel?.emit('signal', { to: id, description: pc.localDescription });
  }).catch(error => toast(`تعذر بدء الاتصال: ${error.message}`));
  return pc;
}
function removeCallPeer(id) {
  const pc = peers.get(id); if (pc) { pc.close(); peers.delete(id); }
  deferredDescriptions.delete(id); deferredCandidates.delete(id); signalSockets.delete(id);
  remoteMediaElements.delete(id);
  document.querySelectorAll(`[data-peer="${CSS.escape(String(id))}"]`).forEach(node => node.remove());
}
async function processDescription(id, packet) {
  const pc = makePeer(id, false);
  if (!pc) { deferredDescriptions.set(id, packet); return; }
    await pc.setRemoteDescription(packet.description);
  const candidates = deferredCandidates.get(id) || [];
  for (const candidate of candidates) await pc.addIceCandidate(candidate).catch(() => {});
  deferredCandidates.delete(id);
  if (packet.description.type === 'offer') {
    const answer = await pc.createAnswer(); await pc.setLocalDescription(answer);
    (signalSockets.get(id) || socket)?.emit('signal', { to: id, description: pc.localDescription });
  }
}
async function receiveSignal(packet, source = socket) {
  if (packet.from) { signalSockets.set(packet.from, source); if (connectionMode === 'direct' && packet.description?.type === 'call-start') callPeerId = packet.from; }
  if (packet.description?.type === 'call-end-all') { closeCall(false); toast('انتهت المكالمة.'); return; }
  if (packet.description?.type === 'call-end') {
    const id = packet.from; removeCallPeer(id);
    if (connectionMode === 'direct' && (!peers.size || callPeerId === id)) closeCall(false);
    else if (connectionMode === 'host' && callOwnerId === id) closeCall(false);
    return;
  }
  if (packet.description?.type === 'call-roster') {
    if (!localStream || connectionMode !== 'host') return;
    const participants = packet.description.participants || [];
    const expected = new Set(participants.filter(id => typeof id === 'string' && id !== window._socketId));
    for (const id of peers.keys()) if (!expected.has(id)) removeCallPeer(id);
    for (const entry of participants) {
      const id = typeof entry === 'string' ? entry : '';
      if (!id || id === window._socketId || peers.has(id)) continue;
      const initiate = String(window._socketId).localeCompare(id) < 0;
      makePeer(id, initiate, source);
    }
    $('callStatus').textContent = `${participants.length} مشاركون · اتصال مباشر داخل الشبكة`;
    return;
  }
  if (packet.description?.type === 'call-start') {
    if (localStream) return;
    if (acceptingCall) return;
    acceptingCall = true;
    playNotification('call');
    try {
      const mode = packet.description.mode === 'video' ? 'video' : 'audio';
      callOwnerId = packet.from;
      if (await showDialog(mode === 'video' ? 'دعوة لمكالمة فيديو' : 'دعوة لمكالمة صوتية', 'هل تريد الانضمام إلى المكالمة المحلية؟', { yesNo: true })) await startCall(mode, false, source);
      else { source?.emit('signal', { to: packet.from, description: { type: 'call-end' } }); callOwnerId = null; callPeerId = null; }
    } finally { acceptingCall = false; }
    return;
  }
  if (packet.description) {
    if (!localStream) { deferredDescriptions.set(packet.from, packet); return; }
    await processDescription(packet.from, packet);
  }
  if (packet.candidate) {
    const pc = peers.get(packet.from);
    if (!pc?.remoteDescription) {
      const list = deferredCandidates.get(packet.from) || []; list.push(packet.candidate); deferredCandidates.set(packet.from, list);
    } else await pc.addIceCandidate(packet.candidate).catch(() => {});
  }
}
 $('endCall').onclick = () => closeCall();
function closeCall(notify = true) {
  if (notify && callMode) {
    if (connectionMode === 'host' && callOwnerId === window._socketId) (callTransport || socket)?.emit('signal', { description: { type: 'call-end-all' } });
    else if (connectionMode === 'host') (callTransport || socket)?.emit('signal', { description: { type: 'call-end' } });
    else if (peers.size) for (const [id] of peers) (signalSockets.get(id) || callTransport || socket)?.emit('signal', { to: id, description: { type: 'call-end' } });
    else if (callPeerId) (callTransport || socket)?.emit('signal', { to: callPeerId, description: { type: 'call-end' } });
  }
  for (const peer of peers.values()) peer.close();
  peers.clear(); deferredDescriptions.clear(); deferredCandidates.clear(); signalSockets.clear(); callTransport = null; callPeerId = null; callOwnerId = null;
  localStream?.getTracks().forEach(track => track.stop()); localStream = null; callMode = null;
  $('callPanel').classList.add('hidden'); $('audioNodes').innerHTML = '';
  $('videoGrid').innerHTML = ''; remoteMediaElements.clear();
}
window.addEventListener('beforeunload', () => { if (callMode) (callTransport || socket)?.emit('signal', { ...(connectionMode === 'direct' && callPeerId ? { to: callPeerId } : {}), description: { type: 'call-end' } }); socket?.disconnect(); });
window.wasel.lanInfo().then(info => {
  if (!connected) $('connectionText').textContent = `بانتظار مضيف على الشبكة · ${info.hostname}`;
});

function applyTheme(theme) {
  const resolved = theme === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : theme;
  document.documentElement.dataset.theme = resolved;
}
function renderNetworkSettings(view) {
  networkSettingsView = view;
  const select = $('networkInterfaceSelect');
  const previous = appSettings.networkInterface || 'auto';
  select.replaceChildren(new Option('تلقائي — جميع البطاقات', 'auto'));
  for (const adapter of view.adapters || []) {
    const option = new Option(`${adapter.name} · ${adapter.address}${adapter.mac ? ` · ${adapter.mac}` : ''}`, adapter.id);
    select.add(option);
  }
  select.value = [...select.options].some(option => option.value === previous) ? previous : 'auto';
  updateNetworkSummary();
  $('settingsVersion').textContent = `واصل Java ${view.appVersion || ''} · ${view.hostname || ''}`;
}
function updateNetworkSummary() {
  if (!networkSettingsView) return;
  const selected = $('networkInterfaceSelect').value;
  const adapters = networkSettingsView.adapters || [];
  const shown = selected === 'auto' ? adapters : adapters.filter(adapter => adapter.id === selected);
  $('networkInfoText').textContent = shown.length
    ? shown.map(adapter => `${adapter.address}${adapter.netmask ? ` / ${adapter.netmask}` : ''} — ${adapter.name}`).join(' · ')
    : 'لم يتم العثور على بطاقة شبكة IPv4؛ تحقق من اتصال الجهاز بالشبكة.';
}
async function loadSettings(openDialog = false) {
  try {
    const view = await window.wasel.getSettings();
    appSettings = view.settings || appSettings;
    renderNetworkSettings(view);
    $('discoveryIntervalSelect').value = String(appSettings.discoveryIntervalMs || 1800);
    $('autoConnectCheck').checked = appSettings.autoConnect !== false;
    $('openAtLoginCheck').checked = Boolean(appSettings.openAtLogin);
    $('themeSelect').value = appSettings.theme || 'light';
    $('defaultSaveDirectory').value = appSettings.defaultSaveDirectory || '';
    $('soundNotificationsCheck').checked = appSettings.soundNotifications !== false;
    $('sharePrintersCheck').checked = appSettings.sharePrinters === true;
    try { printerView = await window.wasel.getPrinters(); renderPrinterSettings(printerView); }
    catch (error) { $('printerList').textContent = `تعذر قراءة الطابعات المحلية: ${error.message}`; }
    const rememberedName = localStorage.getItem('wasel-name');
    $('displayNameInput').value = rememberedName || '';
    applyTheme(appSettings.theme || 'light');
    $('connectionModeSelect').value = connectionMode;
    if (rememberedName && connectionMode === 'direct') startPeerMode();
    if (openDialog) $('settingsDialog').showModal();
    else if (!rememberedName) $('settingsDialog').showModal();
  } catch (error) { toast(`تعذر تحميل الإعدادات: ${error.message}`); }
}
$('settingsBtn').onclick = () => loadSettings(true);
$('connectionModeSelect').onchange = event => setConnectionMode(event.target.value);
$('closeSettingsBtn').onclick = () => $('settingsDialog').close();
$('networkInterfaceSelect').onchange = updateNetworkSummary;
$('saveSettingsBtn').onclick = async () => {
  const name = $('displayNameInput').value.trim().slice(0, 40);
  if (!name) { toast('اكتب الاسم الذي سيظهر للآخرين.'); $('displayNameInput').focus(); return; }
  const previousAutoConnect = appSettings.autoConnect;
  const next = {
    networkInterface: $('networkInterfaceSelect').value,
    discoveryIntervalMs: Number($('discoveryIntervalSelect').value),
    autoConnect: $('autoConnectCheck').checked,
    openAtLogin: $('openAtLoginCheck').checked,
    theme: $('themeSelect').value,
    defaultSaveDirectory: $('defaultSaveDirectory').value.trim(),
    soundNotifications: $('soundNotificationsCheck').checked,
    sharePrinters: $('sharePrintersCheck').checked,
    sharedPrinterIds: [...document.querySelectorAll('[data-shared-printer]:checked')].map(input => input.value)
  };
  $('saveSettingsBtn').disabled = true;
  try {
    const view = await window.wasel.saveSettings(next);
    appSettings = view.settings;
    localStorage.setItem('wasel-name', name); myName = name;
    if (connectionMode === 'direct') {
      if (p2pActive) for (const item of peerSockets.values()) if (item.socket.connected) item.socket.emit('update-profile', { name });
      startPeerMode();
    } else if (connected) socket.emit('update-profile', { name });
    renderNetworkSettings(view); applyTheme(appSettings.theme);
    printerView = await window.wasel.getPrinters(); renderPrinterSettings(printerView);
    $('settingsDialog').close(); toast('تم حفظ إعدادات هذا الجهاز.');
    if (appSettings.autoConnect && !previousAutoConnect && discoveredRoom && !connected && !connecting) connect(discoveredRoom.address, discoveredRoom.port, discoveredRoom.filePort);
  } catch (error) { toast(`تعذر حفظ الإعدادات: ${error.message}`); }
  finally { $('saveSettingsBtn').disabled = false; }
};
$('connectDetectedBtn').onclick = () => {
  if (!discoveredRoom) return toast('لم يُكتشف مضيف بعد.');
  if (connectionMode !== 'host') setConnectionMode('host').then(() => connect(discoveredRoom.address, discoveredRoom.port, discoveredRoom.filePort));
  else connect(discoveredRoom.address, discoveredRoom.port, discoveredRoom.filePort);
};
$('connectHostBtn').onclick = () => {
  const address = $('manualHostInput').value.trim();
  if (!/^\d{1,3}(?:\.\d{1,3}){3}$/.test(address) || address.split('.').some(octet => Number(octet) > 255)) return toast('أدخل عنوان IPv4 صحيحاً للمضيف.');
  if (connectionMode !== 'host') setConnectionMode('host').then(() => connect(address, 43821, 43822));
  else connect(address, 43821, 43822);
};
$('copyLocalIpBtn').onclick = async () => {
  const text = $('networkInfoText').textContent;
  const address = text.match(/\b(?:\d{1,3}\.){3}\d{1,3}\b/)?.[0];
  if (!address) return toast('لا يوجد عنوان IP متاح للنسخ.');
  try { await navigator.clipboard.writeText(address); toast(`تم نسخ ${address}`); }
  catch (_) { $('manualHostInput').value = address; $('manualHostInput').focus(); toast('نُقل عنوان IP إلى خانة الاتصال اليدوي.'); }
};
$('themeSelect').onchange = event => applyTheme(event.target.value);
$('chooseSaveDirectoryBtn').onclick = async () => {
  try {
    const result = await window.wasel.chooseSaveDirectory();
    if (result.path) $('defaultSaveDirectory').value = result.path;
  } catch (error) { toast(`تعذر اختيار مجلد الحفظ: ${error.message}`); }
};
window.wasel.onSettingsUpdated(view => { appSettings = view.settings || appSettings; renderNetworkSettings(view); applyTheme(appSettings.theme); });
loadSettings(false);
