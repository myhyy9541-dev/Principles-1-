const $ = id => document.getElementById(id);
const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
let myName = localStorage.getItem('wasel-name') || '';
if (!myName) {
  myName = (prompt('اكتب اسمك الظاهر للفريق:') || 'مستخدم').trim().slice(0, 40) || 'مستخدم';
  localStorage.setItem('wasel-name', myName);
}
let clientId = localStorage.getItem('wasel-client-id');
if (!clientId) { clientId = `w${crypto.randomUUID().replaceAll('-', '')}`; localStorage.setItem('wasel-client-id', clientId); }
let connected = false, connecting = false, localPeerEndpoint = null;
let pendingFile = null, localStream = null, callMode = null, currentMembers = [], socket = null;
const peerSockets = new Map(), connectionSockets = new Map();
const bluetoothConnections = new Map(), bluetoothSockets = new Map();
const incomingBluetoothTransfers = new Map();
const bluetoothChatRate = new Map();
let activeBluetoothSocket = null;
const isFramedPeerTransport = transport => transport === 'bluetooth' || transport === 'wifi-frame-v1';
function loadAndroidHistory() {
  if (!window.FURSAN_ANDROID) return [];
  try { const saved = JSON.parse(localStorage.getItem('fursan-android-history') || '[]'); return Array.isArray(saved) ? saved.slice(-500) : []; } catch (_) { return []; }
}
function persistAndroidHistory() {
  if (!window.FURSAN_ANDROID) return;
  try { localStorage.setItem('fursan-android-history', JSON.stringify(allMessages.slice(-500))); } catch (_) {}
}
function loadAndroidGroups() {
  if (!window.FURSAN_ANDROID) return [];
  try { const saved = JSON.parse(localStorage.getItem('fursan-android-groups') || '[]'); return Array.isArray(saved) ? saved.slice(-200) : []; } catch (_) { return []; }
}
function persistAndroidGroups() {
  if (!window.FURSAN_ANDROID) return;
  try { localStorage.setItem('fursan-android-groups', JSON.stringify(groups.slice(-200))); } catch (_) {}
}
function loadAndroidContacts() {
  if (!window.FURSAN_ANDROID) return [];
  try {
    const saved = JSON.parse(localStorage.getItem('fursan-android-contacts') || '[]');
    if (!Array.isArray(saved)) return [];
    return saved.filter(item => item && /^[a-zA-Z0-9_-]{16,80}$/.test(String(item.clientId || '')))
      .slice(-500).map(item => ({
        clientId: String(item.clientId),
        name: String(item.name || 'نظير').replace(/[\r\n\0]/g, ' ').slice(0, 40),
        platform: item.platform === 'windows' ? 'windows' : 'android',
        online: false
      }));
  } catch (_) { return []; }
}
function persistAndroidContacts() {
  if (!window.FURSAN_ANDROID) return;
  try {
    const saved = contacts.filter(item => /^[a-zA-Z0-9_-]{16,80}$/.test(String(item.clientId || '')))
      .slice(-500).map(item => ({ clientId: item.clientId, name: String(item.name || 'نظير').slice(0, 40), platform: item.platform === 'windows' ? 'windows' : 'android' }));
    localStorage.setItem('fursan-android-contacts', JSON.stringify(saved));
  } catch (_) {}
}
let contacts = loadAndroidContacts(), groups = loadAndroidGroups(), currentChat = 'general', allMessages = loadAndroidHistory(), searchTerm = '';
const fileEndpoints = new Map();
const readSent = new Set();
let acceptingCall = false, startingCall = false;
const peers = new Map();
const deferredDescriptions = new Map();
const deferredCandidates = new Map();
const syncedGroups = new Set();

function toast(message) {
  const element = $('toast');
  element.textContent = message;
  element.classList.remove('hidden');
  setTimeout(() => element.classList.add('hidden'), 3500);
}
function formatTime(value) { return new Date(value || Date.now()).toLocaleTimeString('ar', { hour: '2-digit', minute: '2-digit' }); }
function fileUrl(id) {
  if (window.FURSAN_ANDROID) {
    const endpoint = fileEndpoints.get(String(id));
    if (endpoint?.endsWith(':local')) return '#';
    if (!endpoint || !/^[0-9a-f-]{36}$/i.test(String(id))) return '#';
    return `http://${endpoint}/api/files/${encodeURIComponent(id)}`;
  }
  if (!localPeerEndpoint || !/^[0-9a-f-]{36}$/i.test(String(id))) return '#';
  return `http://${localPeerEndpoint.address}:${localPeerEndpoint.port}/api/files/${encodeURIComponent(id)}`;
}
function endpointSockets(target = {}) {
  const chosen = [socket?.connected ? socket : null];
  if (target.groupId) for (const id of groups.find(group => group.id === target.groupId)?.members || []) chosen.push(peerSockets.get(id), bluetoothSockets.get(id));
  else if (target.channel === 'direct' && target.toId) chosen.push(peerSockets.get(target.toId), bluetoothSockets.get(target.toId));
  else for (const peer of new Set([...peerSockets.values(), ...bluetoothSockets.values()])) chosen.push(peer);
  return [...new Set(chosen.filter(item => item?.connected))];
}
function currentMemberList() {
  const members = new Map([...peerSockets].map(([id]) => [id, { clientId: id, name: contacts.find(item => item.clientId === id)?.name || 'نظير', platform: contacts.find(item => item.clientId === id)?.platform || 'windows' }]));
  if (!window.FURSAN_ANDROID) for (const contact of contacts) if (contact.platform === 'android' && contact.online) members.set(contact.clientId, { ...contact });
  return [...members.values()];
}
function callPeerIds() {
  const ids = new Set([...peerSockets].filter(([, peer]) => peer.transport !== 'bluetooth').map(([id]) => id));
  if (!window.FURSAN_ANDROID) for (const contact of contacts) if (contact.platform === 'android' && contact.online) ids.add(contact.clientId);
  return [...ids].filter(id => id !== clientId);
}
function emitToTarget(event, payload, target = {}) {
  let endpoints = endpointSockets(target);
  if (!window.FURSAN_ANDROID) endpoints = endpoints.filter(peer => peer === socket || peer.transport !== 'bluetooth');
  for (const peer of endpoints) peer.emit(event, payload);
}
function signalToPeer(client, payload) {
  const packet = { ...payload, toClientId: client };
  const peer = peerSockets.get(client);
  if (peer?.transport === 'bluetooth') return false;
  if (peer?.connected) peer.emit('signal', packet);
  else if (socket?.connected && contacts.some(item => item.clientId === client && item.platform === 'android' && item.online)) socket.emit('signal', packet);
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
    const url = fileUrl(message.id);
    const directLocal = isFramedPeerTransport(message.transport);
    const directHistory = message.transport === 'bluetooth-history' || message.transport === 'wifi-frame-v1-history';
    const link = directLocal
      ? `href="#" data-bt-download="${escapeHtml(message.id)}"`
      : directHistory ? 'href="#" data-bt-unavailable="1"' : `href="${url}"`;
    const label = directHistory ? 'ملف سابق غير مخزن؛ اطلب إعادة مشاركته' : `ملف مشترك · ${escapeHtml(message.user)} · ${formatSize(message.size)}`;
    const preview = !directLocal && !directHistory && /^image\/(png|jpeg|gif|webp|avif)$/.test(String(message.mime || ''))
      ? `<img class="image-preview" src="${url}?preview=1" alt="${escapeHtml(message.name)}" loading="lazy">` : '';
    content = `<a class="file-card" download="${escapeHtml(message.name)}" ${link}><span class="file-icon">▤</span><span class="file-label">${escapeHtml(message.name)}<small>${label}</small></span><span>${bluetoothHistory ? 'غير مخزن' : 'تنزيل'}</span></a>${preview}`;
  }
  const status = mine && message.channel !== 'general' ? `<span class="delivery-state">${(message.readBy || []).length ? '✓✓ تمت القراءة' : (message.deliveredTo || []).length ? '✓ تم التسليم' : '◷ قيد التسليم'}</span>` : '';
  row.innerHTML = `<div class="msg-avatar">${initial}</div><div class="bubble-wrap"><div class="msg-meta"><b>${escapeHtml(message.user || 'النظام')}</b><span class="msg-time">${formatTime(message.time)}</span>${status}</div><div class="bubble">${content}</div></div>`;
  box.append(row);
  row.querySelector('[data-bt-download]')?.addEventListener('click', event => { event.preventDefault(); downloadBluetoothFile(message.id, message.name).catch(error => toast(error.message)); });
  row.querySelector('[data-bt-unavailable]')?.addEventListener('click', event => { event.preventDefault(); toast('النسخة التاريخية غير محفوظة على هذا الهاتف؛ اطلب إعادة مشاركة الملف.'); });
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
function renderMessages() {
  const box = $('messages'); box.innerHTML = '';
  const list = allMessages.filter(message => messageBelongs(message) && (!searchTerm || String(message.text || message.name || '').toLocaleLowerCase().includes(searchTerm.toLocaleLowerCase())));
  for (const message of list) addMessage(message);
  if (!list.length) box.innerHTML = '<div class="welcome"><div class="welcome-icon">✦</div><h2>لا توجد رسائل هنا بعد</h2><p>ابدأ المحادثة مع فريقك على الشبكة المحلية.</p></div>';
  $('searchCount').textContent = searchTerm ? `${list.length} نتيجة` : '';
  if (connected) for (const message of list) if (message.senderClientId !== clientId && message.channel !== 'general' && !(message.readBy || []).includes(clientId) && !readSent.has(message.id)) { readSent.add(message.id); emitToTarget('mark-read', { id: message.id }, { channel: 'direct', toId: message.senderClientId }); }
}
function formatSize(bytes = 0) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} ك.ب` : `${(bytes / 1024 / 1024).toFixed(1)} م.ب`;
}
async function downloadBluetoothFile(id, name) {
  const stored = await window.FursanBluetoothFiles.getBlob(String(id));
  const url = URL.createObjectURL(stored.blob);
  const link = document.createElement('a'); link.href = url; link.download = name || stored.metadata.name || 'ملف'; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
function handleBluetoothEvent(connection, event, data, acknowledge) {
  if (event === 'create-group' && window.FURSAN_ANDROID) {
    const id = String(data?.id || '');
    const members = [...new Set((Array.isArray(data?.members) ? data.members : []).filter(member => /^[a-zA-Z0-9_-]{16,80}$/.test(member)))];
    if (/^[0-9a-f-]{36}$/i.test(id) && members.includes(clientId)) {
      groups = [...groups.filter(item => item.id !== id), { id, name: String(data.name || 'مجموعة').replace(/[\r\n\0]/g, ' ').slice(0, 60), members, ownerId: String(data.ownerId || '') }];
      persistAndroidGroups(); renderChatOptions();
    }
    acknowledge?.({ ok: true }); return;
  }
  if (event === 'chat-message' && window.FURSAN_ANDROID) {
    if (typeof data?.text !== 'string' || data.text.length > 5000 || !/^[0-9a-f-]{36}$/i.test(String(data.id || ''))) { acknowledge?.({ ok: false, error: 'الرسالة غير صالحة أو تتجاوز حد الحجم.' }); return; }
    if (data?.groupId && !groups.some(group => group.id === data.groupId && group.members.includes(clientId))) { acknowledge?.({ ok: false, error: 'هذه الرسالة ليست موجهة إلى مجموعة مسجلة على الجهاز.' }); return; }
    if (data?.channel === 'direct' && !data.groupId && data.toId !== clientId) { acknowledge?.({ ok: false, error: 'هذه الرسالة ليست موجهة إلى هذا الجهاز.' }); return; }
    if (!data.groupId && !['direct', 'general', 'broadcast'].includes(data.channel)) { acknowledge?.({ ok: false, error: 'نوع المحادثة غير صالح.' }); return; }
    const peerId = String(connection.remoteProfile?.clientId || connection.address || 'bluetooth-peer');
    const recent = (bluetoothChatRate.get(peerId) || []).filter(time => Date.now() - time < 60_000);
    if (recent.length >= 40) { acknowledge?.({ ok: false, error: 'تم تجاوز حد الرسائل المؤقت؛ حاول لاحقًا.' }); return; }
    recent.push(Date.now()); bluetoothChatRate.set(peerId, recent);
    handleEvent(event, data, connection, false); acknowledge?.({ ok: true }); return;
  }
  if (event === 'mark-read' && window.FURSAN_ANDROID) {
    const message = allMessages.find(item => item.id === data?.id);
    if (message) { message.readBy ||= []; const reader = connection.remoteProfile?.clientId; if (reader && !message.readBy.includes(reader)) message.readBy.push(reader); persistAndroidHistory(); renderMessages(); }
    acknowledge?.({ ok: true }); return;
  }
  if (event === 'upload-init') {
    const id = String(data?.transferId || '');
    const size = Number(data?.size);
    if (!window.FURSAN_ANDROID || !/^[0-9a-f-]{36}$/i.test(id) || !Number.isSafeInteger(size) || size < 1 || size > 10 * 1024 * 1024 || incomingBluetoothTransfers.size >= 4) {
      acknowledge?.({ ok: false, error: 'بيانات الملف غير صالحة أو تجاوزت حد النقل المتزامن.' }); return;
    }
    const name = String(data.name || 'ملف').replace(/[\\/\r\n\0]/g, '_').slice(0, 180) || 'ملف';
    incomingBluetoothTransfers.set(id, { size, offset: 0, name, mime: String(data.mime || 'application/octet-stream'), channel: data.channel === 'general' ? 'general' : 'direct', toId: data.toId, groupId: data.groupId, recipientIds: Array.isArray(data.recipientIds) ? data.recipientIds : [], connection });
    acknowledge?.({ ok: true, offset: 0 }); return;
  }
  if (event === 'upload-chunk') {
    (async () => {
      const id = String(data?.transferId || '');
      const transfer = incomingBluetoothTransfers.get(id);
      if (!transfer || Number(data.offset) !== transfer.offset || typeof data.data !== 'string' || data.data.length > 44_000 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(data.data)) throw new Error('مقطع الملف غير صالح أو خارج الترتيب.');
      const binary = atob(data.data);
      if (!binary.length || binary.length > 32 * 1024 || transfer.offset + binary.length > transfer.size) throw new Error('حجم مقطع Bluetooth غير صالح.');
      const bytes = new Uint8Array(binary.length);
      for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
      await window.FursanBluetoothFiles.putChunk(id, transfer.offset, bytes);
      transfer.offset += bytes.length;
      acknowledge?.({ ok: true, offset: transfer.offset });
    })().catch(error => acknowledge?.({ ok: false, error: error.message || 'تعذر حفظ مقطع الملف.' }));
    return;
  }
  if (event === 'upload-complete') {
    (async () => {
      const id = String(data?.transferId || '');
      const transfer = incomingBluetoothTransfers.get(id);
      if (!transfer || transfer.offset !== transfer.size) throw new Error('لم يكتمل استلام الملف.');
      const sender = connection.remoteProfile || {};
      const message = { id, type: 'file', name: transfer.name, mime: transfer.mime, size: transfer.size, user: sender.name || 'نظير', senderId: sender.clientId || '', senderClientId: sender.clientId || '', channel: transfer.channel, toId: transfer.toId || clientId, groupId: transfer.groupId, recipientIds: transfer.recipientIds, time: Date.now(), transport: connection.transport, deliveredTo: [], readBy: [] };
      await window.FursanBluetoothFiles.commit({ id, name: message.name, mime: message.mime, size: message.size });
      fileEndpoints.set(id, `${connection.transport}:local`);
      if (!allMessages.some(item => item.id === id)) allMessages.push(message);
      incomingBluetoothTransfers.delete(id); persistAndroidHistory(); renderMessages();
      acknowledge?.({ ok: true, fileId: id });
    })().catch(error => acknowledge?.({ ok: false, error: error.message || 'تعذر إكمال الملف.' }));
    return;
  }
  if (event === 'upload-cancel') {
    const id = String(data?.transferId || ''); incomingBluetoothTransfers.delete(id);
    window.FursanBluetoothFiles.remove(id).catch(() => {}); return;
  }
  if (event === 'file-message' && window.FURSAN_ANDROID && isFramedPeerTransport(connection.transport)) {
    handleEvent(event, { ...data, transport: `${connection.transport}-history` }, connection, false); return;
  }
  handleEvent(event, data, connection, false);
}
function renderMembers(members) {
  const previousIds = new Set(currentMembers.map(member => member.id));
  currentMembers = members.filter(member => member.clientId !== clientId).map(member => ({ ...member, id: member.clientId }));
  $('memberCount').textContent = currentMembers.length;
  $('members').innerHTML = currentMembers.map(user => `<button class="member member-button" data-chat="user:${escapeHtml(user.clientId)}"><span class="avatar">${escapeHtml(user.name.slice(0, 1))}</span>${escapeHtml(user.name)}<span class="green-dot"></span></button>`).join('');
  if (localStream && callMode) currentMembers.filter(user => !previousIds.has(user.id) && peerSockets.get(user.id)?.transport !== 'bluetooth').forEach(user => makePeer(user.id, true));
}
function refreshMembers() { renderMembers(currentMemberList()); }
function renderChatOptions() {
  const select = $('chatSelect');
  const selected = currentChat;
  select.innerHTML = '<option value="general">المحادثة العامة</option>' + contacts.filter(contact => contact.clientId !== clientId).map(contact => `<option value="user:${escapeHtml(contact.clientId)}">خاص: ${escapeHtml(contact.name)}${contact.online ? '' : ' (غير متصل)'}</option>`).join('') + groups.map(group => `<option value="group:${escapeHtml(group.id)}">مجموعة: ${escapeHtml(group.name)}</option>`).join('');
  if ([...select.options].some(option => option.value === selected)) select.value = selected;
  $('chatList').innerHTML = `<button class="room-item ${currentChat === 'general' ? 'active' : ''}" data-chat="general"><span class="hash">#</span><span>عام</span><span class="online-pill">عام</span></button>` + contacts.filter(contact => contact.clientId !== clientId).map(contact => `<button class="room-item ${currentChat === `user:${contact.clientId}` ? 'active' : ''}" data-chat="user:${escapeHtml(contact.clientId)}"><span class="avatar">${escapeHtml(contact.name.slice(0,1))}</span><span>${escapeHtml(contact.name)}</span>${contact.online ? '<span class="green-dot"></span>' : '<span class="offline-mark">○</span>'}</button>`).join('') + groups.map(group => `<button class="room-item ${currentChat === `group:${group.id}` ? 'active' : ''}" data-chat="group:${escapeHtml(group.id)}"><span class="hash">#</span><span>${escapeHtml(group.name)}</span></button>`).join('');
  document.querySelectorAll('[data-chat]').forEach(button => button.onclick = () => selectChat(button.dataset.chat));
}
function selectChat(chat) {
  currentChat = chat; $('chatSelect').value = chat;
  if (chat === 'general') $('chatTitle').textContent = 'المحادثة العامة';
  else if (chat.startsWith('user:')) $('chatTitle').textContent = `محادثة خاصة · ${contacts.find(item => item.clientId === chat.slice(5))?.name || 'مستخدم'}`;
  else $('chatTitle').textContent = `مجموعة · ${groups.find(item => item.id === chat.slice(6))?.name || 'مجموعة'}`;
  if (window.FURSAN_ANDROID && chat.startsWith('user:')) {
    const peerId = chat.slice(5);
    const contact = contacts.find(item => item.clientId === peerId);
    if (contact?.platform === 'android' && !peerSockets.get(peerId)?.connected && !bluetoothSockets.get(peerId)?.connected) {
      $('connectionText').textContent = `جارٍ البحث عن ${contact.name} والاتصال عبر Wi‑Fi Direct…`;
      $('wifiDirectStatus').textContent = `جارٍ ربط ${contact.name}؛ أبقِ التطبيق مفتوحًا على الهاتفين.`;
      const result = window.wasel.connectWifiDirectContact?.(peerId) || 'unavailable';
      if (typeof result === 'string' && result.startsWith('error:')) {
        $('connectionText').textContent = 'تعذر بدء اتصال Wi‑Fi Direct';
        toast(result.slice(6));
      } else if (result === 'unavailable') {
        $('connectionText').textContent = 'اتصال Wi‑Fi Direct غير متاح في هذه النسخة';
        toast('أعد تثبيت أحدث إصدار من فرسان لان.');
      }
    }
  }
  renderChatOptions(); renderMessages();
}
function handleEvent(event, data, source, local = false) {
  switch (event) {
    case 'connect':
      if (local) { connected = true; connecting = false; window._socketId = data?.id; $('connectionText').textContent = 'متصل بالشبكة المحلية — P2P'; $('networkTitle').textContent = 'شبكة الأقران'; }
      else if (window.FURSAN_ANDROID) { connected = true; $('connectionText').textContent = source?.transport === 'bluetooth' ? 'متصل مباشرةً عبر Bluetooth Classic' : `متصل مباشرةً عبر Wi‑Fi · ${source?._fursanAddress || ''}`; $('networkTitle').textContent = 'شبكة الأقران'; }
      break;
    case 'disconnect':
      connectionSockets.delete(source?._fursanAddress || '');
      if (isFramedPeerTransport(source?.transport)) {
        bluetoothChatRate.delete(String(source.remoteProfile?.clientId || source.address || 'bluetooth-peer'));
        for (const [id, transfer] of incomingBluetoothTransfers) if (transfer.connection === source) {
          incomingBluetoothTransfers.delete(id);
          window.FursanBluetoothFiles?.remove(id).catch(() => {});
        }
      }
      for (const [id, peer] of peerSockets) if (peer === source) { peerSockets.delete(id); peers.get(id)?.close(); peers.delete(id); }
      for (const [id, peer] of bluetoothSockets) if (peer === source) bluetoothSockets.delete(id);
      for (const [address, peer] of bluetoothConnections) if (peer === source) bluetoothConnections.delete(address);
      contacts = contacts.map(contact => ({ ...contact, online: peerSockets.has(contact.clientId) }));
      persistAndroidContacts();
      if (window.FURSAN_ANDROID) connected = [...connectionSockets.values()].some(connection => connection.connected);
      renderChatOptions(); refreshMembers();
      if (local) { connected = false; connecting = true; $('connectionText').textContent = 'انقطع الاتصال المحلي؛ جارٍ إعادة المحاولة…'; }
      else if (window.FURSAN_ANDROID && !connected) $('connectionText').textContent = 'لا يوجد نظير متصل — جارٍ البحث عبر Wi‑Fi…';
      break;
    case 'connect_error': if (local) $('connectionText').textContent = 'تعذر تشغيل نقطة P2P المحلية…'; else if (window.FURSAN_ANDROID) $('connectionText').textContent = 'تعذر الوصول إلى أحد أقران Wi‑Fi…'; break;
    case 'identity': if (local) { clientId = data.clientId; localStorage.setItem('wasel-client-id', clientId); } break;
    case 'peer-profile':
      if (!local && data?.clientId && data.clientId !== clientId) {
        peerSockets.set(data.clientId, source);
        if (source?.transport === 'bluetooth') bluetoothSockets.set(data.clientId, source);
        const item = { clientId: data.clientId, name: String(data.name || 'نظير'), platform: data.platform || 'windows', online: true };
        contacts = [item, ...contacts.filter(contact => contact.clientId !== item.clientId)];
        persistAndroidContacts();
        renderChatOptions(); refreshMembers();
        for (const group of groups) source.emit('create-group', group);
        if (localStream && callMode && !peers.has(data.clientId)) makePeer(data.clientId, true);
      }
      break;
    case 'contacts': {
      const merged = new Map(contacts.map(c => [c.clientId, c]));
      for (const item of data || []) if (item.clientId !== clientId) merged.set(item.clientId, { ...merged.get(item.clientId), ...item, online: local ? Boolean(item.online) : peerSockets.has(item.clientId) });
      contacts = [...merged.values()]; persistAndroidContacts(); renderChatOptions(); refreshMembers(); break;
    }
    case 'groups': {
      const merged = new Map(groups.map(group => [group.id, group]));
      const newlyLearned = [];
      for (const group of data || []) { if (!merged.has(group.id)) newlyLearned.push(group); merged.set(group.id, { ...merged.get(group.id), ...group }); }
      groups = [...merged.values()]; renderChatOptions(); persistAndroidGroups();
      for (const group of newlyLearned) if (!local && !syncedGroups.has(group.id)) {
        syncedGroups.add(group.id);
        socket?.emit('create-group', group);
        for (const peer of peerSockets.values()) if (peer !== source) peer.emit('create-group', group);
      }
      break;
    }
    case 'history': {
      const merged = new Map(allMessages.map(message => [message.id, message]));
      for (const message of data || []) {
        const item = { ...merged.get(message.id), ...message };
        if (window.FURSAN_ANDROID && message.type === 'file' && isFramedPeerTransport(source?.transport)) item.transport = `${source.transport}-history`;
        else if (window.FURSAN_ANDROID && message.type === 'file' && source?._fursanAddress) fileEndpoints.set(message.id, source._fursanAddress);
        merged.set(message.id, item);
      }
      allMessages = [...merged.values()].sort((a, b) => (a.time || 0) - (b.time || 0)); persistAndroidHistory(); renderMessages(); break;
    }
    case 'members': if (local) refreshMembers(); break;
    case 'system-message': if (local) addMessage({ type: 'system', text: data.text }); break;
    case 'chat-message': {
      if (!local && data?.toId === clientId && source?.connected) socket?.emit('cache-message', data);
      const index = allMessages.findIndex(message => message.id === data.id);
      if (index >= 0) allMessages[index] = { ...allMessages[index], ...data }; else allMessages.push(data);
      persistAndroidHistory(); renderMessages(); break;
    }
    case 'file-message': {
      const item = { type: 'file', ...data };
      if (window.FURSAN_ANDROID && isFramedPeerTransport(source?.transport)) item.transport = `${source.transport}-history`;
      else if (window.FURSAN_ANDROID && source?._fursanAddress) fileEndpoints.set(data.id, source._fursanAddress);
      if (!allMessages.some(message => message.id === data.id)) allMessages.push(item);
      persistAndroidHistory(); renderMessages(); break;
    }
    case 'message-receipt': {
      const message = allMessages.find(item => item.id === data.id);
      if (message) { const key = data.kind === 'read' ? 'readBy' : 'deliveredTo'; message[key] ||= []; if (!message[key].includes(data.clientId)) message[key].push(data.clientId); persistAndroidHistory(); renderMessages(); }
      break;
    }
    case 'notice': toast(data); break;
    case 'signal': receiveSignal(data).catch(error => toast(`تعذر إعداد الاتصال: ${error.message}`)); break;
  }
}
function connect(address, port, local = false) {
  if (!window.io) return toast('ملف الاتصال المحلي غير متوفر؛ أعد تثبيت التطبيق.');
  const key = `${address}:${port}`;
  if (connectionSockets.get(key)?.connected || connectionSockets.get(key)?.active) return;
  if (local) { localPeerEndpoint = { address: '127.0.0.1', port }; connecting = true; }
  else $('connectionText').textContent = `اكتشاف نظير ${address}…`;
  const connection = window.io(`http://${address}:${port}`, { transports: ['websocket', 'polling'], timeout: 5000, reconnection: true });
  connection._fursanAddress = key; connectionSockets.set(key, connection);
  const events = ['connect_error', 'disconnect', 'identity', 'history', 'members', 'contacts', 'groups', 'system-message', 'chat-message', 'file-message', 'message-receipt', 'signal', 'notice', 'peer-profile'];
  connection.on('connect', () => {
    if (local) socket = connection;
    connection.emit('join', { name: myName, clientId, local, platform: window.FURSAN_ANDROID ? 'android' : 'windows' });
    handleEvent('connect', { id: connection.id }, connection, local);
  });
  for (const event of events) connection.on(event, data => handleEvent(event, data, connection, local));
}
window.wasel.onPeerFound(room => {
  const key = `${room.address}:${room.port}`;
  if (lanAddressesSeen.has(key)) return;
  lanAddressesSeen.add(key);
  if (window.FURSAN_ANDROID && room.transport === 'wifi-frame-v1') {
    $('connectionText').textContent = `تم اكتشاف نظير Android · ${room.name}؛ جارٍ إنشاء اتصال Wi‑Fi مباشر…`;
    const result = window.wasel.connectWifiPeer(room.address, room.port, room.instanceId);
    if (typeof result === 'string' && result.startsWith('error:')) {
      $('connectionText').textContent = 'تعذر بدء اتصال Wi‑Fi المباشر';
      toast(result.slice(6));
    } else if (result === 'waiting-for-peer') $('connectionText').textContent = `تم اكتشاف ${room.name}؛ جارٍ انتظار اتصال النظير…`;
    return;
  }
  connect(room.address, room.port, false);
});
const lanAddressesSeen = new Set();
window.wasel.onPeerStarted(data => {
  $('peerBanner').classList.remove('hidden');
  $('peerAddress').textContent = `نظير جاهز · العناوين: ${data.addresses.join(' · ')} · المنفذ ${data.port}`;
  connect('127.0.0.1', data.port, true);
});
window.wasel.onNotice(message => toast(message));
function registerBluetoothSocket(address, remoteProfile = null, sendHello = false, transport = 'bluetooth') {
  const key = String(address || 'bluetooth-peer');
  let connection = transport === 'bluetooth' ? bluetoothConnections.get(key) : connectionSockets.get(`${transport}:${key}`);
  if (connection?.connected) return connection;
  const localProfile = { clientId, name: myName, platform: window.FURSAN_ANDROID ? 'android' : 'windows' };
  const sendFrame = frame => window.FURSAN_ANDROID
    ? transport === 'wifi-frame-v1' ? window.wasel.sendWifiFrame(key, frame) : window.wasel.sendBluetoothFrame(key, frame)
    : window.wasel.sendBluetoothFrame(frame);
  connection = new window.FursanBluetoothClient({ address: key, localProfile, sendFrame, transport });
  if (transport === 'bluetooth') bluetoothConnections.set(key, connection);
  connectionSockets.set(connection._fursanAddress, connection);
  if (!window.FURSAN_ANDROID && transport === 'bluetooth') activeBluetoothSocket = connection;
  const events = ['history', 'members', 'contacts', 'groups', 'system-message', 'chat-message', 'file-message', 'message-receipt', 'mark-read', 'create-group', 'notice', 'upload-init', 'upload-chunk', 'upload-complete', 'upload-cancel'];
  if (transport === 'wifi-frame-v1') events.push('signal');
  for (const event of events) connection.on(event, (data, acknowledge) => handleBluetoothEvent(connection, event, data, acknowledge));
  connection.on('peer-profile', profile => {
    if (transport === 'bluetooth') bluetoothSockets.set(profile.clientId, connection);
    handleEvent('peer-profile', profile, connection, false);
  });
  connection.on('connect', data => handleEvent('connect', data, connection, false));
  connection.on('disconnect', data => handleEvent('disconnect', data, connection, false));
  if (remoteProfile) {
    connection.remoteProfile = { ...remoteProfile };
    if (transport === 'bluetooth') bluetoothSockets.set(remoteProfile.clientId, connection);
    handleEvent('peer-profile', remoteProfile, connection, false);
  }
  if (sendHello) Promise.resolve(connection.sendHello()).catch(error => toast(`تعذر إرسال هوية Bluetooth: ${error.message}`));
  return connection;
}

async function refreshBluetoothDevices() {
  const select = $('bluetoothDeviceList');
  select.replaceChildren();
  let devices = [];
  try { devices = await Promise.resolve(window.wasel.listBluetoothDevices?.() || []); }
  catch (error) { toast(`تعذر قراءة أجهزة Bluetooth: ${error.message}`); }
  if (!Array.isArray(devices) || !devices.length) {
    const option = document.createElement('option'); option.value = ''; option.textContent = window.FURSAN_ANDROID ? 'لا توجد أجهزة مقترنة — اقترن بها من إعدادات النظام' : 'لم يُعثر على منفذ COM؛ أنشئ SPP من إعدادات Bluetooth'; select.append(option); return;
  }
  for (const device of devices) {
    const option = document.createElement('option');
    option.value = String(device.address || device.path || '');
    option.textContent = `${String(device.name || device.label || option.value).slice(0, 100)}${device.address ? ` · ${device.address}` : ''}`;
    select.append(option);
  }
}

const connectionSettings = $('connectionSettings');
const connectionMethod = $('connectionMethod');
function selectConnectionMethod(method) {
  if (!window.FURSAN_ANDROID && method !== 'bluetooth') method = 'bluetooth';
  connectionMethod.value = method;
  $('connectionModeCaption').textContent = connectionMethod.selectedOptions[0]?.textContent || 'الاتصالات';
  const bluetooth = method === 'bluetooth';
  $('wifiPeerPanel').classList.toggle('hidden', bluetooth || !window.FURSAN_ANDROID);
  $('bluetoothPanel').classList.toggle('hidden', !bluetooth);
  $('wifiDirectMethod').classList.toggle('hidden', method !== 'wifi-direct');
  $('localHotspotMethod').classList.toggle('hidden', method !== 'local-hotspot');
  $('existingWifiMethod').classList.toggle('hidden', method !== 'wifi-lan');
}
if (!window.FURSAN_ANDROID) {
  for (const option of connectionMethod.options) if (option.value !== 'bluetooth') option.hidden = true;
}
selectConnectionMethod(window.FURSAN_ANDROID ? 'wifi-direct' : 'bluetooth');
async function activateBluetoothControls() {
  if (window.FURSAN_ANDROID) {
    const result = window.wasel.startBluetooth();
    if (result === 'permission-required') $('bluetoothState').textContent = 'بانتظار إذن Android…';
    else $('bluetoothState').textContent = 'بدء مستمع Bluetooth…';
  }
  await refreshBluetoothDevices();
}
connectionMethod.addEventListener('change', async () => {
  selectConnectionMethod(connectionMethod.value);
  if (connectionMethod.value === 'bluetooth') await activateBluetoothControls();
});

$('bluetoothBtn').onclick = async () => {
  connectionSettings.open = true;
  selectConnectionMethod('bluetooth');
  await activateBluetoothControls();
};
$('bluetoothRefresh').onclick = async () => {
  if (window.FURSAN_ANDROID) window.wasel.startBluetooth();
  await refreshBluetoothDevices();
};
$('bluetoothConnect').onclick = async () => {
  const address = $('bluetoothDeviceList').value;
  if (!address) return toast('اقرن جهازاً أو اختر منفذ Bluetooth أولاً.');
  try {
    const result = await Promise.resolve(window.wasel.connectBluetooth(address));
    if (typeof result === 'string' && result.startsWith('error:')) throw new Error(result.slice(6));
    if (window.FURSAN_ANDROID && result !== 'connecting') throw new Error('لم يبدأ اتصال Bluetooth؛ افتح لوحة Bluetooth وتأكد أن المستمع جاهز على الجهازين.');
    $('bluetoothState').textContent = window.FURSAN_ANDROID ? `جارٍ الاتصال بـ ${address}…` : `منفذ ${address} مفتوح؛ بانتظار النظير…`;
  } catch (error) { toast(`تعذر بدء اتصال Bluetooth: ${error.message}`); }
};
$('bluetoothDisconnect').onclick = async () => {
  try {
    if (window.FURSAN_ANDROID) {
      const address = $('bluetoothDeviceList').value;
      if (address) window.wasel.disconnectBluetooth(address);
    } else await window.wasel.disconnectBluetooth();
  } catch (error) { toast(`تعذر قطع Bluetooth: ${error.message}`); }
};

window.wasel.onBluetoothEvent(event => {
  const detail = event?.detail ? { ...event.detail, type: event.type } : event;
  if (!detail) return;
  if (detail.type === 'ready') { $('bluetoothState').textContent = 'مستمع Bluetooth جاهز'; refreshBluetoothDevices(); }
  else if (detail.type === 'port-open') $('bluetoothState').textContent = `منفذ ${detail.path} مفتوح؛ جارٍ المصافحة…`;
  else if (detail.type === 'peer-connected') {
    const address = window.FURSAN_ANDROID ? detail.address : detail.path;
    const profile = detail.clientId ? { clientId: detail.clientId, name: detail.name, platform: detail.platform } : null;
    registerBluetoothSocket(address, profile, window.FURSAN_ANDROID);
    $('bluetoothState').textContent = `متصل مباشرةً بـ ${detail.name || 'نظير Bluetooth'}`;
  } else if (detail.type === 'peer-pending') $('bluetoothState').textContent = 'تم العثور على النظير؛ جارٍ تهيئة الاتصال المحلي…';
  else if (detail.type === 'peer-disconnected') {
    for (const [key, peer] of bluetoothConnections) if (peer.remoteProfile?.clientId === detail.clientId) peer.close();
    $('bluetoothState').textContent = 'انقطع النظير؛ منفذ Bluetooth ما زال مفتوحاً';
  } else if (detail.type === 'frame') {
    const peer = window.FURSAN_ANDROID ? bluetoothConnections.get(detail.address) : activeBluetoothSocket;
    peer?.receive(detail.frame);
  } else if (detail.type === 'disconnected') {
    for (const peer of bluetoothConnections.values()) peer.close();
    bluetoothConnections.clear(); bluetoothSockets.clear(); activeBluetoothSocket = null;
    $('bluetoothState').textContent = 'غير متصل';
  } else if (detail.type === 'notice') {
    $('bluetoothState').textContent = detail.message || 'تعذر اتصال Bluetooth.';
    toast(detail.message || 'تعذر اتصال Bluetooth.');
  }
});

window.wasel.onWifiEvent?.(event => {
  const detail = event?.detail ? { ...event.detail, type: event.type } : event;
  if (!detail) return;
  if (detail.type === 'pairing-started') {
    $('connectionText').textContent = `تم مسح رمز النظير؛ جارٍ الاتصال مباشرةً بـ ${detail.address}:43821…`;
    toast('تمت قراءة رمز فرسان لان، جارٍ بدء الاتصال المباشر.');
  } else if (detail.type === 'local-hotspot-started') {
    $('wifiHotspotSsid').textContent = detail.ssid || 'غير متاح';
    $('wifiHotspotPassphrase').textContent = detail.passphrase || 'بدون كلمة مرور';
    $('wifiHotspotAddresses').textContent = `عناوين هذا الهاتف: ${detail.addresses || 'جارٍ اكتشاف عنوان الشبكة'} · منفذ المراسلة ${detail.port || 43821}`;
    $('wifiHotspotCredentials').classList.remove('hidden');
    $('wifiStartHotspot').classList.add('hidden');
    $('wifiStopHotspot').classList.remove('hidden');
    $('wifiHotspotQr').classList.remove('hidden');
    $('wifiHotspotStatus').textContent = detail.message || 'النقطة جاهزة؛ اتصل بها من الهاتف الآخر ثم ارجع إلى التطبيق.';
    $('connectionText').textContent = 'نقطة اتصال محلية جاهزة؛ بانتظار اتصال الهاتف الآخر';
    toast('نقطة الاتصال جاهزة. استخدم اسم الشبكة وكلمة المرور على الهاتف الآخر.');
  } else if (detail.type === 'local-hotspot-status') {
    $('wifiHotspotStatus').textContent = detail.message || 'جارٍ إنشاء نقطة الاتصال…';
  } else if (detail.type === 'local-hotspot-failed') {
    $('wifiHotspotStatus').textContent = detail.message || 'تعذر إنشاء نقطة الاتصال.';
    $('wifiStartHotspot').classList.remove('hidden');
    $('wifiStopHotspot').classList.add('hidden');
    toast(detail.message || 'تعذر إنشاء نقطة الاتصال المحلية.');
  } else if (detail.type === 'local-hotspot-stopped') {
    $('wifiHotspotCredentials').classList.add('hidden');
    $('wifiHotspotQrImage').classList.add('hidden');
    $('wifiHotspotQr').classList.add('hidden');
    $('wifiStopHotspot').classList.add('hidden');
    $('wifiStartHotspot').classList.remove('hidden');
    $('wifiHotspotStatus').textContent = detail.message || 'توقفت نقطة الاتصال المحلية.';
  } else if (detail.type === 'wifi-direct-status') {
    $('wifiDirectStatus').textContent = detail.message || 'تحديث حالة Wi‑Fi Direct.';
  } else if (detail.type === 'wifi-direct-contact-found') {
    if (/^[a-zA-Z0-9_-]{16,80}$/.test(String(detail.clientId || '')) && detail.clientId !== clientId) {
      const known = contacts.find(contact => contact.clientId === detail.clientId);
      contacts = [{
        clientId: detail.clientId,
        name: known?.name || String(detail.name || 'هاتف Android').replace(/[\r\n\0]/g, ' ').slice(0, 40),
        platform: 'android',
        online: peerSockets.has(detail.clientId)
      }, ...contacts.filter(contact => contact.clientId !== detail.clientId)];
      persistAndroidContacts();
      renderChatOptions();
      refreshMembers();
    }
    if (currentChat === `user:${detail.clientId}`) $('wifiDirectStatus').textContent = `عُثر على ${detail.name || 'جهة الاتصال'}؛ جارٍ إعداد الاتصال المباشر…`;
  } else if (detail.type === 'wifi-direct-peers') {
    const list = $('wifiDirectPeerList');
    list.replaceChildren(new Option('اختر هاتفًا قريبًا', ''));
    for (const peer of detail.peers || []) {
      if (!peer.address) continue;
      list.add(new Option(`${peer.name} — ${peer.status}`, peer.address));
    }
    if (!(detail.peers || []).length) $('wifiDirectStatus').textContent = 'لم يُعثر على أجهزة. افتح شاشة Wi‑Fi Direct على الهاتف الآخر ثم أعد البحث.';
  } else if (detail.type === 'peer-connected') {
    registerBluetoothSocket(detail.address, null, true, 'wifi-frame-v1');
    $('connectionText').textContent = `متصل مباشرةً عبر Wi‑Fi · ${detail.address}`;
  } else if (detail.type === 'frame') {
    connectionSockets.get(`wifi-frame-v1:${detail.address}`)?.receive(detail.frame);
  } else if (detail.type === 'peer-disconnected') {
    lanAddressesSeen.delete(`${detail.address}:43821`);
    connectionSockets.get(`wifi-frame-v1:${detail.address}`)?.close();
  } else if (detail.type === 'notice') {
    if (detail.address) lanAddressesSeen.delete(`${detail.address}:43821`);
    toast(detail.message || 'تعذر اتصال Wi‑Fi المباشر.');
  }
});

if (window.FURSAN_ANDROID) {
  const discovery = window.wasel.startWifiDirectDiscovery?.(clientId, myName) || 'unavailable';
  if (typeof discovery === 'string' && discovery.startsWith('error:')) {
    $('wifiDirectStatus').textContent = discovery.slice(6);
  } else if (discovery === 'unavailable') {
    $('wifiDirectStatus').textContent = 'اكتشاف Wi‑Fi Direct التلقائي غير متاح في هذه النسخة.';
  }
  const localAddresses = window.wasel.getLocalWifiAddresses?.() || '';
  $('wifiLocalAddresses').textContent = localAddresses
    ? `عناوين هذا الهاتف: ${localAddresses} · المنفذ 43821`
    : 'لم يظهر عنوان شبكة محلية بعد؛ اتصل بنقطة اتصال أو Wi‑Fi Direct ثم أعد فتح التطبيق.';
  $('wifiPeerConnect').onclick = () => {
    const address = $('wifiPeerAddress').value.trim();
    if (!address) return toast('أدخل عنوان IP للهاتف الآخر كما يظهر في تفاصيل الشبكة.');
    const result = window.wasel.connectWifiPeerManually(address);
    if (typeof result === 'string' && result.startsWith('error:')) {
      $('connectionText').textContent = 'تعذر بدء الاتصال اليدوي عبر Wi‑Fi';
      toast(result.slice(6));
    } else {
      $('connectionText').textContent = `جارٍ الاتصال مباشرةً بالعنوان ${address}:43821…`;
      toast('بدأ الاتصال المباشر؛ تحقق من أن الهاتف الآخر مفتوح على فرسان لان.');
    }
  };
  $('wifiShowPairingQr').onclick = () => {
    try {
      const result = JSON.parse(window.wasel.createWifiPairingQr());
      if (result.error) return toast(result.error);
      $('wifiPairingQrImage').src = `data:image/png;base64,${result.qrPng}`;
      $('wifiPairingQrImage').classList.remove('hidden');
      $('wifiPeerAddress').value = result.address || '';
      $('wifiPairingHelp').textContent = `اعرض الرمز على الهاتف الآخر وامسحه من فرسان لان. عنوان هذه الواجهة ${result.address}:${result.port}؛ الاتصال محلي ومباشر.`;
    } catch (error) { toast(`تعذر إنشاء رمز الاقتران: ${error.message}`); }
  };
  $('wifiScanPairingQr').onclick = () => {
    const state = window.wasel.scanWifiPairingQr();
    if (state === 'unavailable') toast('ماسح QR غير متاح في هذه النسخة.');
  };
  $('wifiDirectCreateGroup').onclick = () => window.wasel.createWifiDirectGroup();
  $('wifiDirectDiscover').onclick = () => window.wasel.discoverWifiDirectPeers();
  $('wifiDirectJoin').onclick = () => {
    const address = $('wifiDirectPeerList').value;
    if (!address) return toast('ابحث أولًا ثم اختر هاتفًا من قائمة Wi‑Fi Direct.');
    window.wasel.connectWifiDirectPeer(address);
  };
  $('wifiStartHotspot').onclick = () => {
    $('wifiHotspotStatus').textContent = 'بانتظار إذن Android وبدء النقطة…';
    const result = window.wasel.startLocalHotspot();
    if (result === 'unavailable') $('wifiHotspotStatus').textContent = 'إنشاء النقطة المحلية غير متاح في هذه النسخة.';
  };
  $('wifiStopHotspot').onclick = () => window.wasel.stopLocalHotspot();
  $('wifiHotspotQr').onclick = () => {
    try {
      const result = JSON.parse(window.wasel.createHotspotWifiQr());
      if (result.error) return toast(result.error);
      $('wifiHotspotQrImage').src = `data:image/png;base64,${result.qrPng}`;
      $('wifiHotspotQrImage').classList.remove('hidden');
    } catch (error) { toast(`تعذر إنشاء رمز الشبكة: ${error.message}`); }
  };
  $('wifiPeerAddress').addEventListener('keydown', event => {
    if (event.key === 'Enter') $('wifiPeerConnect').click();
  });
}

function sendText() {
  const input = $('messageInput');
  const text = input.value.trim().slice(0, 5000);
  if (!text) return;
  if (!connected) return toast('انتظر اتصال النظير المحلي');
  const target = selectedTarget();
  const payload = { id: crypto.randomUUID(), text, ...target };
  if (target.channel === 'general') payload.recipientIds = contacts.map(contact => contact.clientId).filter(id => id !== clientId);
  if (window.FURSAN_ANDROID && !endpointSockets(target).length) return toast('لا يوجد اتصال مباشر بهذا المستخدم عبر Wi‑Fi أو Bluetooth.');
  emitToTarget('chat', payload, target);
  if (window.FURSAN_ANDROID) {
    allMessages.push({ ...payload, type: 'text', user: myName, senderId: clientId, senderClientId: clientId, time: Date.now(), deliveredTo: [], readBy: [] });
    persistAndroidHistory(); renderMessages();
  }
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
  for (const file of files) {
    if (!file.size) { toast(`تم تجاهل الملف الفارغ: ${file.name}`); continue; }
    if (file.size > 10 * 1024 * 1024) { toast(`تم تجاهل الملف الكبير: ${file.name}`); continue; }
    const name = folder && file.webkitRelativePath ? file.webkitRelativePath : file.name;
    const job = { id: crypto.randomUUID(), file, name, target: selectedTarget(), offset: 0, paused: false, canceled: false, state: 'بالانتظار' };
    transfers.set(job.id, job); renderTransfers(); runUpload(job);
  }
}
function renderTransfers() {
  $('transferList').innerHTML = [...transfers.values()].map(job => {
    const percent = job.total ? Math.min(100, Math.floor(job.offset * 100 / job.total)) : 0;
    return `<div class="transfer-item"><div class="transfer-title"><b>${escapeHtml(job.name)}</b><span>${job.state} · ${percent}%</span><button data-pause="${job.id}" class="call-control">${job.paused ? 'استئناف' : 'إيقاف مؤقت'}</button><button data-cancel="${job.id}" class="call-control">إلغاء</button></div><progress max="100" value="${percent}"></progress></div>`;
  }).join('');
  document.querySelectorAll('[data-pause]').forEach(button => button.onclick = () => { const job = transfers.get(button.dataset.pause); if (job) { job.paused = !job.paused; job.state = job.paused ? 'متوقف مؤقتاً' : 'جارٍ الرفع'; if (!job.paused) { job.resume?.(); job.resume = null; runUpload(job); } renderTransfers(); } });
  document.querySelectorAll('[data-cancel]').forEach(button => button.onclick = () => { const job = transfers.get(button.dataset.cancel); if (job) { job.canceled = true; for (const peer of endpointSockets(job.target)) { const id = job.transferIds?.get(peer._fursanAddress); if (id) peer.emit('upload-cancel', { transferId: id }); } transfers.delete(job.id); renderTransfers(); } });
}
function socketAck(connection, event, payload) {
  return new Promise((resolve, reject) => {
    if (!connection?.connected) return reject(new Error('انقطع اتصال النظير'));
    const timer = setTimeout(() => reject(new Error('انتهت مهلة النقل')), 12000);
    connection.emit(event, payload, response => { clearTimeout(timer); if (response?.ok) resolve(response); else reject(new Error(response?.error || 'تعذر إكمال النقل')); });
  });
}
async function runUpload(job) {
  if (job.running) return;
  job.running = true;
  const destinations = endpointSockets(job.target);
  if (!destinations.length) { job.state = 'لا يوجد نظير متصل'; job.paused = true; job.running = false; renderTransfers(); return; }
  job.total = job.file.size * destinations.length; job.completedPeers ||= new Set(); job.offset = job.file.size * job.completedPeers.size; job.transferIds ||= new Map();
  try {
    job.completedPeers ||= new Set();
    for (let peerIndex = 0; peerIndex < destinations.length && !job.canceled; peerIndex++) {
      const connection = destinations[peerIndex];
      if (job.completedPeers.has(connection._fursanAddress)) continue;
      const transferKey = connection._fursanAddress;
      const transferId = job.transferIds.get(transferKey) || crypto.randomUUID();
      job.transferIds.set(transferKey, transferId); job.activeTransferId = transferId;
      const recipientIds = job.target.channel === 'general' ? contacts.map(contact => contact.clientId).filter(id => id !== clientId) : undefined;
      const init = await socketAck(connection, 'upload-init', { transferId, name: job.name, mime: job.file.type, size: job.file.size, ...job.target, recipientIds });
      let offset = init.offset;
      while (!job.canceled && offset < job.file.size) {
        if (job.paused) { await new Promise(resolve => { job.resume = resolve; }); continue; }
        const end = Math.min(offset + 32 * 1024, job.file.size);
        const bytes = new Uint8Array(await job.file.slice(offset, end).arrayBuffer());
        let binary = ''; for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
        const response = await socketAck(connection, 'upload-chunk', { transferId, offset, data: btoa(binary) });
        offset = response.offset; job.offset = peerIndex * job.file.size + offset; job.state = `إرسال مباشر إلى ${peerIndex + 1}/${destinations.length}`; renderTransfers();
      }
      if (!job.canceled) {
        const completed = await socketAck(connection, 'upload-complete', { transferId });
        if (window.FURSAN_ANDROID && completed.fileId && !job.androidLocalFileRecorded) {
          const framedPeer = isFramedPeerTransport(connection.transport);
          if (framedPeer) {
            try { await window.FursanBluetoothFiles.saveBlob(completed.fileId, job.file, job.name, job.file.type || 'application/octet-stream'); }
            catch (error) { toast(`أُرسل الملف، لكن تعذر حفظ نسخة محلية: ${error.message}`); }
          }
          fileEndpoints.set(completed.fileId, framedPeer ? `${connection.transport}:local` : connection._fursanAddress);
          allMessages.push({ id: completed.fileId, type: 'file', name: job.name, mime: job.file.type || 'application/octet-stream', size: job.file.size, user: myName, senderId: clientId, senderClientId: clientId, ...job.target, recipientIds, time: Date.now(), deliveredTo: [], readBy: [], ...(framedPeer ? { transport: connection.transport } : {}) });
          job.androidLocalFileRecorded = true; persistAndroidHistory(); renderMessages();
        }
        job.transferIds.delete(transferKey); job.completedPeers.add(transferKey);
      }
    }
    if (!job.canceled) { job.state = 'اكتمل الإرسال المباشر'; job.offset = job.total; renderTransfers(); setTimeout(() => { transfers.delete(job.id); renderTransfers(); }, 5000); }
  } catch (error) {
    if (!job.canceled) { job.state = 'تعذر النقل — اضغط استئناف'; job.paused = true; toast(error.message); }
  } finally { job.running = false; renderTransfers(); }
}
$('chatSelect').onchange = event => selectChat(event.target.value);
$('newGroupBtn').onclick = () => {
  if (!connected) return toast('اتصل بشبكة الأقران لإنشاء مجموعة.');
  const eligible = contacts.filter(contact => contact.clientId !== clientId);
  if (!eligible.length) return toast('لا يوجد مستخدمون آخرون معروفون بعد.');
  const choices = eligible.map((item, index) => `${index + 1}. ${item.name}${item.online ? '' : ' (غير متصل)'}`).join('\n');
  const name = prompt('اسم المجموعة:'); if (!name?.trim()) return;
  const input = prompt(`أدخل أرقام الأعضاء مفصولة بفواصل:\n${choices}`, eligible.map((_, index) => index + 1).join(','));
  if (!input) return;
  const members = input.split(',').map(value => eligible[Number(value.trim()) - 1]?.clientId).filter(Boolean);
  emitToTarget('create-group', { id: crypto.randomUUID(), name, members, ownerId: clientId }, { channel: 'general' });
};
$('newGroupMobileBtn').onclick = $('newGroupBtn').onclick;
$('broadcastBtn').onclick = $('broadcastHere').onclick = () => {
  const text = prompt('اكتب رسالة البث التي ستصل إلى جميع المستخدمين المعروفين، بمن فيهم غير المتصلين:');
  if (text?.trim()) {
    const message = { id: crypto.randomUUID(), type: 'text', channel: 'broadcast', text: text.trim(), user: myName, senderId: clientId, senderClientId: clientId, recipientIds: contacts.map(contact => contact.clientId).filter(id => id !== clientId), time: Date.now(), deliveredTo: [], readBy: [] };
    emitToTarget('broadcast', { id: message.id, text: message.text, recipientIds: message.recipientIds }, { channel: 'general' });
    if (window.FURSAN_ANDROID) { allMessages.push(message); persistAndroidHistory(); renderMessages(); }
  }
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
  $('callStatus').textContent = 'اتصال WebRTC مباشر بين الأقران';
  $('callPanel').classList.remove('hidden'); $('videoGrid').innerHTML = '';
  $('cameraBtn').classList.toggle('hidden', mode !== 'video');
  $('muteBtn').textContent = 'كتم الصوت'; $('cameraBtn').textContent = 'إيقاف الكاميرا';
  if (mode === 'video') addVideo('أنت', localStream, true);
}
async function startCall(mode, initiator = true) {
  if (callMode || startingCall) return toast('هناك مكالمة قائمة بالفعل. أنهها قبل بدء مكالمة أخرى.');
  if (!connected) return toast('اتصل بشبكة الأقران أولاً');
  startingCall = true;
  try {
    localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: mode === 'video' });
    showCallPanel(mode);
    if (initiator) {
      // Signaling over one ordered Socket.IO channel: invitation precedes SDP offers.
      callPeerIds().forEach(id => signalToPeer(id, { description: { type: 'call-start', mode } }));
      callPeerIds().forEach(id => makePeer(id, true));
    } else {
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
function makePeer(id, offer) {
  if (!localStream || id === window._socketId) return null;
  let pc = peers.get(id);
  if (pc) return pc;
  pc = new RTCPeerConnection({ iceServers: [] });
  peers.set(id, pc);
  localStream.getTracks().forEach(track => pc.addTrack(track, localStream));
  pc.onicecandidate = event => { if (event.candidate) signalToPeer(id, { candidate: event.candidate }); };
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
    signalToPeer(id, { description: pc.localDescription });
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
    signalToPeer(id, { description: pc.localDescription });
  }
}
async function receiveSignal(packet) {
  if (packet.description?.type === 'call-end') { closeCall(); toast('انتهى الاتصال المباشر.'); return; }
  if (packet.description?.type === 'call-start') {
    if (localStream) return;
    if (acceptingCall) return;
    acceptingCall = true;
    try {
      const mode = packet.description.mode === 'video' ? 'video' : 'audio';
      if (confirm(`بدأ ${mode === 'video' ? 'اتصال فيديو' : 'اتصال صوتي'} محلي. هل تريد الانضمام؟`)) await startCall(mode, false);
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
$('endCall').onclick = () => {
  callPeerIds().forEach(id => signalToPeer(id, { description: { type: 'call-end' } }));
  closeCall();
};
function closeCall() {
  for (const peer of peers.values()) peer.close();
  peers.clear(); deferredDescriptions.clear(); deferredCandidates.clear();
  localStream?.getTracks().forEach(track => track.stop()); localStream = null; callMode = null;
  $('callPanel').classList.add('hidden'); $('audioNodes').innerHTML = '';
}
window.addEventListener('beforeunload', () => { if (callMode) callPeerIds().forEach(id => signalToPeer(id, { description: { type: 'call-end' } })); for (const connection of connectionSockets.values()) connection.disconnect(); });
renderChatOptions(); renderMessages();
if (window.FURSAN_ANDROID) {
  $('connectionText').textContent = 'جارٍ اكتشاف أجهزة فرسان لان عبر Wi‑Fi…';
} else {
  window.wasel.lanInfo().then(info => {
    if (!connected) $('connectionText').textContent = `تشغيل نظير مستقل على ${info.hostname}…`;
    return window.wasel.startPeer();
  }).then(result => { if (result?.ok) { localPeerEndpoint = { address: '127.0.0.1', port: result.port }; connect('127.0.0.1', result.port, true); } else if (result?.error) toast(result.error); }).catch(error => toast(`تعذر تشغيل نظير P2P: ${error.message}`));
}
