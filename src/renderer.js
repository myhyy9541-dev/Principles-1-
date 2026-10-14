const $ = id => document.getElementById(id);
const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
let myName = localStorage.getItem('wasel-name') || '';
if (!myName) {
  myName = (prompt('اكتب اسمك الظاهر للفريق:') || 'مستخدم').trim().slice(0, 40) || 'مستخدم';
  localStorage.setItem('wasel-name', myName);
}
let clientId = localStorage.getItem('wasel-client-id');
if (!clientId) { clientId = `w${crypto.randomUUID().replaceAll('-', '')}`; localStorage.setItem('wasel-client-id', clientId); }
let connected = false, connecting = false, hostMode = false, currentHost = null;
let pendingFile = null, localStream = null, callMode = null, currentMembers = [], socket = null;
let contacts = [], groups = [], currentChat = 'general', allMessages = [], searchTerm = '';
const readSent = new Set();
let acceptingCall = false, startingCall = false;
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
function fileUrl(id) {
  if (!currentHost || !/^[0-9a-f-]{36}$/i.test(String(id))) return '#';
  return `http://${currentHost.address}:${currentHost.port}/api/files/${encodeURIComponent(id)}`;
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
function renderMessages() {
  const box = $('messages'); box.innerHTML = '';
  const list = allMessages.filter(message => messageBelongs(message) && (!searchTerm || String(message.text || message.name || '').toLocaleLowerCase().includes(searchTerm.toLocaleLowerCase())));
  for (const message of list) addMessage(message);
  if (!list.length) box.innerHTML = '<div class="welcome"><div class="welcome-icon">✦</div><h2>لا توجد رسائل هنا بعد</h2><p>ابدأ المحادثة مع فريقك على الشبكة المحلية.</p></div>';
  $('searchCount').textContent = searchTerm ? `${list.length} نتيجة` : '';
  if (connected) for (const message of list) if (message.senderClientId !== clientId && message.channel !== 'general' && !(message.readBy || []).includes(clientId) && !readSent.has(message.id)) { readSent.add(message.id); socket.emit('mark-read', { id: message.id }); }
}
function formatSize(bytes = 0) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} ك.ب` : `${(bytes / 1024 / 1024).toFixed(1)} م.ب`;
}
function renderMembers(members) {
  const previousIds = new Set(currentMembers.map(member => member.id));
  const nextIds = new Set(members.map(member => member.id));
  for (const [id, pc] of peers) if (!nextIds.has(id)) { pc.close(); peers.delete(id); }
  currentMembers = members;
  $('memberCount').textContent = members.length;
  $('members').innerHTML = members.map(user => `<button class="member member-button" data-chat="user:${escapeHtml(user.clientId)}"><span class="avatar">${escapeHtml(user.name.slice(0, 1))}</span>${escapeHtml(user.name)}<span class="green-dot"></span></button>`).join('');
  if (localStream && callMode) members.filter(user => user.id !== window._socketId && !previousIds.has(user.id)).forEach(user => makePeer(user.id, true));
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
function handleEvent(event, data) {
  switch (event) {
    case 'connect':
      connected = true; connecting = false; window._socketId = data?.id;
      $('connectionText').textContent = 'متصل بالشبكة المحلية'; $('roomTitle').textContent = 'غرفة المنشأة';
      break;
    case 'disconnect':
      connected = false; connecting = true; $('connectionText').textContent = 'انقطع الاتصال؛ جارٍ إعادة المحاولة…';
      break;
    case 'connect_error':
      connected = false; $('connectionText').textContent = 'تعذر الاتصال؛ جارٍ إعادة المحاولة…';
      break;
    case 'identity': clientId = data.clientId; localStorage.setItem('wasel-client-id', clientId); break;
    case 'contacts': contacts = data; renderChatOptions(); break;
    case 'groups': groups = data; renderChatOptions(); break;
    case 'history': allMessages = data; renderMessages(); break;
    case 'members': renderMembers(data); break;
    case 'system-message': addMessage({ type: 'system', text: data.text }); break;
    case 'chat-message': {
      const index = allMessages.findIndex(message => message.id === data.id);
      if (index >= 0) allMessages[index] = { ...allMessages[index], ...data }; else allMessages.push(data);
      renderMessages(); break;
    }
    case 'file-message': allMessages.push({ type: 'file', ...data }); renderMessages(); break;
    case 'message-receipt': {
      const message = allMessages.find(item => item.id === data.id);
      if (message) { const key = data.kind === 'read' ? 'readBy' : 'deliveredTo'; message[key] ||= []; if (!message[key].includes(data.clientId)) message[key].push(data.clientId); renderMessages(); }
      break;
    }
    case 'notice': toast(data); break;
    case 'signal': receiveSignal(data).catch(error => toast(`تعذر إعداد الاتصال: ${error.message}`)); break;
  }
}
function connect(address, port) {
  if (connected || connecting) return;
  connecting = true;
  currentHost = { address, port };
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
window.wasel.onRoomFound(room => {
  if (!connected && !connecting && !hostMode) connect(room.address, room.port);
});
window.wasel.onRoomStarted(data => {
  hostMode = true;
  $('roomBanner').classList.remove('hidden');
  $('hostAddress').textContent = `العنوان: ${data.addresses.join(' · ')} — المنفذ ${data.port}`;
  connect('127.0.0.1', data.port);
});
window.wasel.onNotice(message => toast(message));
$('createRoom').onclick = async () => {
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
  socket?.emit('chat', { text, ...target });
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
    const percent = job.file.size ? Math.min(100, Math.floor(job.offset * 100 / job.file.size)) : 0;
    return `<div class="transfer-item"><div class="transfer-title"><b>${escapeHtml(job.name)}</b><span>${job.state} · ${percent}%</span><button data-pause="${job.id}" class="call-control">${job.paused ? 'استئناف' : 'إيقاف مؤقت'}</button><button data-cancel="${job.id}" class="call-control">إلغاء</button></div><progress max="100" value="${percent}"></progress></div>`;
  }).join('');
  document.querySelectorAll('[data-pause]').forEach(button => button.onclick = () => { const job = transfers.get(button.dataset.pause); if (job) { job.paused = !job.paused; job.state = job.paused ? 'متوقف مؤقتاً' : 'جارٍ الرفع'; if (!job.paused) { job.resume?.(); job.resume = null; runUpload(job); } renderTransfers(); } });
  document.querySelectorAll('[data-cancel]').forEach(button => button.onclick = () => { const job = transfers.get(button.dataset.cancel); if (job) { job.canceled = true; socket?.emit('upload-cancel', { transferId: job.id }); transfers.delete(job.id); renderTransfers(); } });
}
function socketAck(event, payload) {
  return new Promise((resolve, reject) => {
    if (!socket?.connected) return reject(new Error('انقطع الاتصال'));
    const timer = setTimeout(() => reject(new Error('انتهت مهلة الرفع')), 12000);
    socket.emit(event, payload, response => { clearTimeout(timer); if (response?.ok) resolve(response); else reject(new Error(response?.error || 'تعذر إكمال النقل')); });
  });
}
async function waitConnected(job) {
  while (!job.canceled && (!connected || !socket?.connected)) {
    job.state = 'بانتظار الشبكة'; renderTransfers();
    await new Promise(resolve => socket?.once('connect', resolve) || setTimeout(resolve, 3000));
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
      const init = await socketAck('upload-init', { transferId: job.id, name: job.name, mime: job.file.type, size: job.file.size, ...job.target });
      job.offset = init.offset;
      while (!job.canceled && !job.paused && job.offset < job.file.size) {
        const end = Math.min(job.offset + 48 * 1024, job.file.size);
        const bytes = new Uint8Array(await job.file.slice(job.offset, end).arrayBuffer());
        let binary = ''; for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
        const response = await socketAck('upload-chunk', { transferId: job.id, offset: job.offset, data: btoa(binary) });
        job.offset = response.offset; renderTransfers();
      }
    }
    if (!job.canceled && job.offset === job.file.size) {
      await socketAck('upload-complete', { transferId: job.id }); job.state = 'اكتمل'; renderTransfers();
      setTimeout(() => { transfers.delete(job.id); renderTransfers(); }, 5000);
    }
  } catch (error) {
    if (!job.canceled) { job.state = 'تعذر النقل — اضغط استئناف'; job.paused = true; toast(error.message); }
  } finally { job.running = false; renderTransfers(); }
}
$('chatSelect').onchange = event => selectChat(event.target.value);
$('newGroupBtn').onclick = () => {
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
async function startCall(mode, initiator = true) {
  if (callMode || startingCall) return toast('هناك مكالمة قائمة بالفعل. أنهها قبل بدء مكالمة أخرى.');
  if (!connected) return toast('اتصل بغرفة محلية أولاً');
  startingCall = true;
  try {
    localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: mode === 'video' });
    showCallPanel(mode);
    if (initiator) {
      // Signaling over one ordered Socket.IO channel: invitation precedes SDP offers.
      socket?.emit('signal', { description: { type: 'call-start', mode } });
      currentMembers.filter(member => member.id !== window._socketId).forEach(member => makePeer(member.id, true));
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
  pc.onicecandidate = event => { if (event.candidate) socket?.emit('signal', { to: id, candidate: event.candidate }); };
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
    socket?.emit('signal', { to: id, description: pc.localDescription });
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
    socket?.emit('signal', { to: id, description: pc.localDescription });
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
  socket?.emit('signal', { description: { type: 'call-end' } });
  closeCall();
};
function closeCall() {
  for (const peer of peers.values()) peer.close();
  peers.clear(); deferredDescriptions.clear(); deferredCandidates.clear();
  localStream?.getTracks().forEach(track => track.stop()); localStream = null; callMode = null;
  $('callPanel').classList.add('hidden'); $('audioNodes').innerHTML = '';
}
window.addEventListener('beforeunload', () => { if (callMode) socket?.emit('signal', { description: { type: 'call-end' } }); socket?.disconnect(); });
window.wasel.lanInfo().then(info => {
  if (!connected) $('connectionText').textContent = `بانتظار مضيف على الشبكة · ${info.hostname}`;
});
