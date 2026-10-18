const $ = id => document.getElementById(id);
const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
const MAX_FILE_BYTES = 1024 * 1024 * 1024, UPLOAD_CHUNK_BYTES = 1024 * 1024;
let myName = localStorage.getItem('wasel-name') || 'مستخدم';
let clientId = localStorage.getItem('wasel-client-id');
if (!clientId) { clientId = `w${crypto.randomUUID().replaceAll('-', '')}`; localStorage.setItem('wasel-client-id', clientId); }
let connected = false, connecting = false, hostMode = false, currentHost = null;
let pendingFile = null, localStream = null, callMode = null, currentMembers = [], socket = null;
let connectionMode = localStorage.getItem('wasel-connection-mode') || 'direct', p2pActive = false, localPeerSocket = null, selfFileAddress = '127.0.0.1';
const peerSockets = new Map(), discoveredPeers = new Map(), signalSockets = new Map();
if (!['direct', 'host'].includes(connectionMode)) connectionMode = 'direct';
let appSettings = { networkInterface: 'auto', discoveryIntervalMs: 1800, autoConnect: true, openAtLogin: false, theme: 'light' };
let discoveredRoom = null, networkSettingsView = null;
let contacts = [], groups = [], currentChat = 'general', allMessages = (() => { try { const saved = JSON.parse(localStorage.getItem('wasel-p2p-history') || '[]'); return Array.isArray(saved) ? saved.slice(-1000) : []; } catch (_) { return []; } })(), searchTerm = '';
const readSent = new Set(), messageSockets = new Map();
let acceptingCall = false, startingCall = false, callTransport = null, callPeerId = null;
const peers = new Map();
const deferredDescriptions = new Map();
const deferredCandidates = new Map();

function toast(message) {
  const element = $('toast');
  element.textContent = message;
  element.classList.remove('hidden');
  setTimeout(() => element.classList.add('hidden'), 3500);
}
function formatTime(value) { return new Date(value || Date.now()).toLocaleTimeString('ar', { hour: '2-digit', minute: '2-digit' }); }
function fileUrl(id, message = {}) {
  if (!/^[0-9a-f-]{36}$/i.test(String(id))) return '#';
  const address = message.fileHost || currentHost?.address;
  const port = message.filePort || currentHost?.filePort || 43822;
  return address ? `http://${address}:${port}/api/files/${encodeURIComponent(id)}` : '#';
}
function addMessage(message) {
  const box = $('messages');
  box.querySelector('.welcome')?.remove();
  if (message.type === 'system') {
    const line = document.createElement('div');
    line.className = 'system-message';
    line.textContent = message.text;
    box.append(line);
    box.scrollTop = box.scrollHeight;
    return;
  }
  const mine = message.senderClientId ? message.senderClientId === clientId : message.senderId === window._socketId;
  const row = document.createElement('div');
  row.className = `message${mine ? ' mine' : ''}`;
  const initial = escapeHtml((message.user || '?').slice(0, 1));
  let content = escapeHtml(message.text || '');
  if (message.type === 'file') {
    const url = fileUrl(message.id, message);
    const preview = /^image\/(png|jpeg|gif|webp|avif)$/.test(String(message.mime || ''))
      ? `<img class="image-preview" src="${url}?preview=1" alt="${escapeHtml(message.name)}" loading="lazy">` : '';
    content = `<a class="file-card" download="${escapeHtml(message.name)}" href="${url}"><span class="file-icon">▤</span><span class="file-label">${escapeHtml(message.name)}<small>ملف مشترك · ${escapeHtml(message.user)} · ${formatSize(message.size)}</small></span><span>تنزيل</span></a>${preview}`;
  }
  const status = mine && message.channel !== 'general' ? `<span class="delivery-state">${(message.readBy || []).length ? '✓✓ تمت القراءة' : (message.deliveredTo || []).length ? '✓ تم التسليم' : '◷ قيد التسليم'}</span>` : '';
  row.innerHTML = `<div class="msg-avatar">${initial}</div><div class="bubble-wrap"><div class="msg-meta"><b>${escapeHtml(message.user || 'النظام')}</b><span class="msg-time">${formatTime(message.time)}</span>${status}</div><div class="bubble">${content}</div></div>`;
  box.append(row);
  box.scrollTop = box.scrollHeight;
}
function messageBelongs(message) {
  if (message.type === 'system') return false;
  if (currentChat === 'general') return message.channel === 'general' || message.channel === 'broadcast';
  if (currentChat.startsWith('user:')) {
    const id = currentChat.slice(5);
    return message.channel === 'direct' && ((message.senderClientId === clientId && message.toId === id) || (message.senderClientId === id && message.toId === clientId));
  }
  return currentChat.startsWith('group:') && message.groupId === currentChat.slice(6);
}
function mergeMessages(messages) {
  const byId = new Map(allMessages.map(message => [message.id, message]));
  for (const message of messages || []) if (message?.id) byId.set(message.id, { ...byId.get(message.id), ...message });
  allMessages = [...byId.values()].sort((a, b) => (a.time || 0) - (b.time || 0)).slice(-5000);
  if (connectionMode === 'direct') {
    try { localStorage.setItem('wasel-p2p-history', JSON.stringify(allMessages.slice(-1000))); } catch (_) { }
  }
}
function renderMessages() {
  const box = $('messages'); box.innerHTML = '';
  const list = allMessages.filter(message => messageBelongs(message) && (!searchTerm || String(message.text || message.name || '').toLocaleLowerCase().includes(searchTerm.toLocaleLowerCase())));
  for (const message of list) addMessage(message);
  if (!list.length) box.innerHTML = '<div class="welcome"><div class="welcome-icon">✦</div><h2>لا توجد رسائل هنا بعد</h2><p>ابدأ المحادثة مع فريقك على الشبكة المحلية.</p></div>';
  $('searchCount').textContent = searchTerm ? `${list.length} نتيجة` : '';
  if (connected) for (const message of list) if (message.senderClientId !== clientId && message.channel !== 'general' && !(message.readBy || []).includes(clientId) && !readSent.has(message.id)) { readSent.add(message.id); (messageSockets.get(message.id) || socket)?.emit('mark-read', { id: message.id }); }
}
function formatSize(bytes = 0) {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} غ.ب`;
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} ك.ب` : `${(bytes / 1024 / 1024).toFixed(1)} م.ب`;
}
function renderMembers(members) {
  if (connectionMode === 'direct') { renderPeerMembers(); return; }
  const previousIds = new Set(currentMembers.map(member => member.id));
  const nextIds = new Set(members.map(member => member.id));
  for (const [id, pc] of peers) if (!nextIds.has(id)) { pc.close(); peers.delete(id); }
  currentMembers = members;
  $('memberCount').textContent = members.length;
  $('members').innerHTML = members.map(user => `<button class="member member-button" data-chat="user:${escapeHtml(user.clientId)}"><span class="avatar">${escapeHtml(user.name.slice(0, 1))}</span>${escapeHtml(user.name)}<span class="green-dot"></span></button>`).join('');
  if (localStream && callMode) members.filter(user => user.id !== window._socketId && !previousIds.has(user.id)).forEach(user => makePeer(user.id, true));
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
  currentChat = chat; $('chatSelect').value = chat;
  if (chat === 'general') $('chatTitle').textContent = 'المحادثة العامة';
  else if (chat.startsWith('user:')) $('chatTitle').textContent = `محادثة خاصة · ${contacts.find(item => item.clientId === chat.slice(5))?.name || 'مستخدم'}`;
  else $('chatTitle').textContent = `مجموعة · ${groups.find(item => item.id === chat.slice(6))?.name || 'مجموعة'}`;
  renderChatOptions(); renderMessages();
}
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
    case 'history':
      if (connectionMode === 'direct') {
        const fromPeer = [...peerSockets.values()].find(item => item.socket === source)?.info;
        const history = (data || []).map(message => {
          if (message.id) messageSockets.set(message.id, source);
          return message.type === 'file' ? { ...message, fileHost: fromPeer?.address || '127.0.0.1', filePort: fromPeer?.filePort || 43822 } : message;
        });
        mergeMessages([...allMessages, ...history]);
      } else allMessages = data;
      renderMessages(); break;
    case 'members': if (connectionMode !== 'direct' || source === localPeerSocket) renderMembers(data); break;
    case 'system-message': addMessage({ type: 'system', text: data.text }); break;
    case 'chat-message': {
      if (data?.id) messageSockets.set(data.id, source);
      const index = allMessages.findIndex(message => message.id === data.id);
      if (index >= 0) allMessages[index] = { ...allMessages[index], ...data }; else allMessages.push(data);
      if (connectionMode === 'direct') mergeMessages(allMessages); renderMessages(); break;
    }
    case 'file-message': {
      if (data?.id) messageSockets.set(data.id, source);
      const fromPeer = [...peerSockets.values()].find(item => item.socket === source)?.info;
      const file = { type: 'file', ...data };
      if (connectionMode === 'direct') { file.fileHost = fromPeer?.address || '127.0.0.1'; file.filePort = fromPeer?.filePort || 43822; mergeMessages([...allMessages, file]); }
      else if (!allMessages.some(item => item.id === file.id)) allMessages.push(file);
      renderMessages(); break;
    }
    case 'message-receipt': {
      const message = allMessages.find(item => item.id === data.id);
      if (message) { const key = data.kind === 'read' ? 'readBy' : 'deliveredTo'; message[key] ||= []; if (!message[key].includes(data.clientId)) message[key].push(data.clientId); renderMessages(); }
      break;
    }
    case 'notice': toast(data); break;
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
  for (const event of ['connect', 'connect_error', 'disconnect', 'identity', 'history', 'members', 'contacts', 'groups', 'system-message', 'chat-message', 'file-message', 'message-receipt', 'signal', 'notice']) {
    socket.on(event, data => {
      if (event === 'connect') { socket.emit('join', { name: myName, clientId }); handleEvent(event, { id: socket.id }); }
      else handleEvent(event, data);
    });
  }
}
function bindPeerSocket(peerSocket, peer = null, local = false) {
  peerSocket.on('connect', () => {
    peerSocket.emit('join', { name: myName, clientId });
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
  for (const event of ['identity', 'history', 'members', 'contacts', 'groups', 'system-message', 'chat-message', 'file-message', 'message-receipt', 'signal', 'notice']) {
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
      } else if (['history', 'chat-message', 'file-message', 'message-receipt', 'signal', 'notice'].includes(event)) {
        handleEvent(event, data, peerSocket);
      }
    });
  }
}
function connectPeer(peer) {
  if (!p2pActive || connectionMode !== 'direct' || !peer?.clientId || peer.clientId === clientId) return;
  const previous = peerSockets.get(peer.clientId);
  if (previous && previous.info.address === peer.address && previous.socket.active) return;
  if (previous) { previous.socket.disconnect(); peerSockets.delete(peer.clientId); }
  const peerSocket = window.io(`http://${peer.address}:${peer.port || 43823}`, { transports: ['websocket', 'polling'], timeout: 5000, reconnection: true });
  peerSockets.set(peer.clientId, { socket: peerSocket, info: peer, remoteSessionId: null });
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
  if (old && old.address !== peer.address) { peerSockets.get(peer.clientId)?.socket.disconnect(); peerSockets.delete(peer.clientId); }
  peer.lastSeen = Date.now(); discoveredPeers.set(peer.clientId, peer);
  if (connectionMode === 'direct' && p2pActive) connectPeer(peer);
  if (connectionMode === 'direct') refreshPeerContacts();
});
setInterval(() => {
  const ttl = Math.max(15000, (appSettings.discoveryIntervalMs || 1800) * 3);
  let changed = false;
  for (const [id, peer] of discoveredPeers) if (Date.now() - (peer.lastSeen || 0) > ttl) {
    const item = peerSockets.get(id); item?.socket.disconnect(); peerSockets.delete(id); discoveredPeers.delete(id); changed = true;
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
$('attachBtn').onclick = () => $('fileInput').click();
$('folderBtn').onclick = () => $('folderInput').click();
$('fileInput').onchange = event => { queueFiles([...event.target.files]); event.target.value = ''; };
$('folderInput').onchange = event => { queueFiles([...event.target.files], true); event.target.value = ''; };
function queueFiles(files, folder = false) {
  if (!files.length) return;
  if (connectionMode === 'direct' && !currentChat.startsWith('user:')) return toast('في وضع P2P اختر محادثة شخص محدد قبل إرسال ملف أو صورة.');
  for (const file of files) {
    if (!file.size) { toast(`تم تجاهل الملف الفارغ: ${file.name}`); continue; }
    if (file.size > MAX_FILE_BYTES) { toast(`تم تجاهل الملف؛ الحد الأقصى 1 غيغابايت: ${file.name}`); continue; }
    const name = folder && file.webkitRelativePath ? file.webkitRelativePath : file.name;
    const job = { id: crypto.randomUUID(), file, name, target: selectedTarget(), offset: 0, paused: false, canceled: false, state: 'بالانتظار' };
    transfers.set(job.id, job); renderTransfers(); runUpload(job);
  }
}
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
  job.running = true;
  try {
    while (!job.canceled && job.offset < job.file.size) {
      if (job.paused) { await new Promise(resolve => { job.resume = resolve; }); continue; }
      await waitConnected(job);
      if (job.canceled) break;
      job.state = 'جارٍ الرفع';
      const transport = uploadSocket(job);
      const init = await socketAck('upload-init', { transferId: job.id, name: job.name, mime: job.file.type, size: job.file.size, ...job.target }, transport);
      job.offset = init.offset;
      while (!job.canceled && !job.paused && job.offset < job.file.size) {
        const end = Math.min(job.offset + UPLOAD_CHUNK_BYTES, job.file.size);
        const bytes = new Uint8Array(await job.file.slice(job.offset, end).arrayBuffer());
        const response = await socketAck('upload-chunk', { transferId: job.id, offset: job.offset, data: encodeBase64(bytes) }, transport);
        job.offset = response.offset; renderTransfers();
      }
    }
    if (!job.canceled && job.offset === job.file.size) {
      await socketAck('upload-complete', { transferId: job.id }, uploadSocket(job)); job.state = 'اكتمل'; renderTransfers();
      setTimeout(() => { transfers.delete(job.id); renderTransfers(); }, 5000);
    }
  } catch (error) {
    if (!job.canceled) { job.state = 'تعذر النقل — اضغط استئناف'; job.paused = true; toast(error.message); }
  } finally { job.running = false; renderTransfers(); }
}
$('chatSelect').onchange = event => selectChat(event.target.value);
$('newGroupBtn').onclick = () => {
  if (connectionMode === 'direct') return toast('المجموعات تُدار حالياً من وضع المضيف المؤقت؛ المحادثة العامة وP2P الفردي متاحان هنا.');
  if (!connected) return toast('اتصل بالغرفة لإنشاء مجموعة.');
  const eligible = contacts.filter(contact => contact.clientId !== clientId);
  if (!eligible.length) return toast('لا يوجد مستخدمون آخرون معروفون بعد.');
  const choices = eligible.map((item, index) => `${index + 1}. ${item.name}${item.online ? '' : ' (غير متصل)'}`).join('\n');
  const name = prompt('اسم المجموعة:'); if (!name?.trim()) return;
  const input = prompt(`أدخل أرقام الأعضاء مفصولة بفواصل:\n${choices}`, eligible.map((_, index) => index + 1).join(','));
  if (!input) return;
  const members = input.split(',').map(value => eligible[Number(value.trim()) - 1]?.clientId).filter(Boolean);
  socket.emit('create-group', { name, members });
};
$('broadcastBtn').onclick = $('broadcastHere').onclick = () => {
  const text = prompt('اكتب رسالة البث التي ستصل إلى جميع المستخدمين المعروفين، بمن فيهم غير المتصلين:');
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
  const video = document.createElement('video');
  video.autoplay = true; video.playsInline = true; video.muted = local; video.srcObject = stream; video.title = label;
  if (local) video.className = 'local-video';
  if (peerId) video.dataset.peer = peerId;
  $('videoGrid').append(video);
  return video;
}
function showCallPanel(mode) {
  callMode = mode;
  $('callTitle').textContent = mode === 'video' ? 'مكالمة فيديو' : 'مكالمة صوتية';
  $('callStatus').textContent = 'اتصال محلي مباشر';
  $('callPanel').classList.remove('hidden'); $('videoGrid').innerHTML = '';
  $('cameraBtn').classList.toggle('hidden', mode !== 'video');
  $('muteBtn').textContent = 'كتم الصوت'; $('cameraBtn').textContent = 'إيقاف الكاميرا';
  if (mode === 'video') addVideo('أنت', localStream, true);
}
async function startCall(mode, initiator = true, incomingTransport = null) {
  if (callMode || startingCall) return toast('هناك مكالمة قائمة بالفعل. أنهها قبل بدء مكالمة أخرى.');
  if (!connected) return toast('اتصل بغرفة محلية أولاً');
  startingCall = true;
  try {
    if (connectionMode === 'direct' && initiator && !currentChat.startsWith('user:')) return toast('اختر مستخدماً من القائمة لبدء مكالمة مباشرة.');
    let directTarget = null;
    if (connectionMode === 'direct' && initiator) {
      directTarget = peerSockets.get(currentChat.slice(5));
      if (!directTarget?.socket.connected || !directTarget.remoteSessionId) return toast('الطرف لم يكتمل اتصاله بعد؛ انتظر ظهوره متصلاً ثم أعد المحاولة.');
    }
    localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: mode === 'video' });
    showCallPanel(mode);
    if (initiator) {
      if (connectionMode === 'direct') {
        callTransport = directTarget.socket; callPeerId = directTarget.remoteSessionId; signalSockets.set(directTarget.remoteSessionId, callTransport);
        callTransport.emit('signal', { to: directTarget.remoteSessionId, targetClientId: directTarget.info.clientId, description: { type: 'call-start', mode } });
        makePeer(directTarget.remoteSessionId, true, callTransport);
      } else {
        callTransport = socket;
        socket?.emit('signal', { description: { type: 'call-start', mode } });
        currentMembers.filter(member => member.id !== window._socketId).forEach(member => makePeer(member.id, true, socket));
      }
    } else {
      callTransport = incomingTransport || socket;
      for (const [id, packet] of deferredDescriptions) await processDescription(id, packet);
      deferredDescriptions.clear();
    }
  } catch (error) {
    localStream?.getTracks().forEach(track => track.stop()); localStream = null;
    toast(`تعذر الوصول إلى الميكروفون/الكاميرا: ${error.message}`);
  } finally { startingCall = false; }
}
$('voiceBtn').onclick = () => startCall('audio');
$('videoBtn').onclick = () => startCall('video');
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
    const stream = event.streams[0];
    if (!stream) return;
    if (callMode === 'video') {
      if (!$(`[data-peer="${id}"]`)) addVideo('مشارك', stream, false, id);
    } else {
      let audio = $(`audio[data-peer="${id}"]`);
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
  if (packet.description?.type === 'call-end') { closeCall(false); toast('انتهى الاتصال المباشر.'); return; }
  if (packet.description?.type === 'call-start') {
    if (localStream) return;
    if (acceptingCall) return;
    acceptingCall = true;
    try {
      const mode = packet.description.mode === 'video' ? 'video' : 'audio';
      if (confirm(`بدأ ${mode === 'video' ? 'اتصال فيديو' : 'اتصال صوتي'} محلي. هل تريد الانضمام؟`)) await startCall(mode, false, source);
      else source?.emit('signal', { to: packet.from, description: { type: 'call-end' } });
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
  if (notify && callMode) (callTransport || socket)?.emit('signal', { ...(connectionMode === 'direct' && callPeerId ? { to: callPeerId } : {}), description: { type: 'call-end' } });
  for (const peer of peers.values()) peer.close();
  peers.clear(); deferredDescriptions.clear(); deferredCandidates.clear(); signalSockets.clear(); callTransport = null; callPeerId = null;
  localStream?.getTracks().forEach(track => track.stop()); localStream = null; callMode = null;
  $('callPanel').classList.add('hidden'); $('audioNodes').innerHTML = '';
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
    theme: $('themeSelect').value
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
window.wasel.onSettingsUpdated(view => { appSettings = view.settings || appSettings; renderNetworkSettings(view); applyTheme(appSettings.theme); });
loadSettings(false);
