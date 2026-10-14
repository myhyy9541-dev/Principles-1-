const $ = id => document.getElementById(id);
const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
let myName = localStorage.getItem('wasel-name') || '';
if (!myName) {
  myName = (prompt('اكتب اسمك الظاهر للفريق:') || 'مستخدم').trim().slice(0, 40) || 'مستخدم';
  localStorage.setItem('wasel-name', myName);
}
let connected = false, connecting = false, hostMode = false, currentHost = null;
let pendingFile = null, localStream = null, callMode = null, currentMembers = [], socket = null;
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
  const mine = message.senderId === window._socketId;
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
  row.innerHTML = `<div class="msg-avatar">${initial}</div><div class="bubble-wrap"><div class="msg-meta"><b>${escapeHtml(message.user || 'النظام')}</b><span class="msg-time">${formatTime(message.time)}</span></div><div class="bubble">${content}</div></div>`;
  box.append(row);
  box.scrollTop = box.scrollHeight;
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
  $('members').innerHTML = members.map(user => `<div class="member"><span class="avatar">${escapeHtml(user.name.slice(0, 1))}</span>${escapeHtml(user.name)}${user.id === window._socketId ? '<span class="green-dot"></span>' : ''}</div>`).join('');
  if (localStream && callMode) members.filter(user => user.id !== window._socketId && !previousIds.has(user.id)).forEach(user => makePeer(user.id, true));
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
    case 'history': data.forEach(addMessage); break;
    case 'members': renderMembers(data); break;
    case 'system-message': addMessage({ type: 'system', text: data.text }); break;
    case 'chat-message': addMessage(data); break;
    case 'file-message': addMessage({ type: 'file', ...data }); break;
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
  for (const event of ['connect', 'connect_error', 'disconnect', 'history', 'members', 'system-message', 'chat-message', 'file-message', 'signal', 'notice']) {
    socket.on(event, data => {
      if (event === 'connect') { socket.emit('join', { name: myName }); handleEvent(event, { id: socket.id }); }
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
  socket?.emit('chat', { text });
  input.value = ''; input.style.height = 'auto';
}
$('sendBtn').onclick = sendText;
$('messageInput').addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); sendText(); }
});
$('messageInput').addEventListener('input', event => {
  event.target.style.height = 'auto'; event.target.style.height = `${Math.min(event.target.scrollHeight, 110)}px`;
});
$('attachBtn').onclick = () => $('fileInput').click();
$('fileInput').onchange = event => {
  const file = event.target.files[0];
  if (!file) return;
  if (file.size > 10 * 1024 * 1024) { toast('حجم الملف يتجاوز 10 ميغابايت'); event.target.value = ''; return; }
  pendingFile = file;
  $('filePreview').textContent = `جاهز للإرسال: ${file.name} (${formatSize(file.size)}) — اضغط إرسال للمشاركة`;
  $('filePreview').classList.remove('hidden');
  $('sendBtn').onclick = sendFile;
};
async function sendFile() {
  if (!pendingFile) return sendText();
  if (!connected) return toast('انتظر اتصال الجهاز بالمضيف');
  const file = pendingFile;
  try {
    const data = await new Promise((resolve, reject) => {
      const reader = new FileReader(); reader.onload = () => resolve(reader.result); reader.onerror = reject; reader.readAsDataURL(file);
    });
    socket?.emit('file', { name: file.name, mime: file.type, data });
    pendingFile = null; $('fileInput').value = ''; $('filePreview').classList.add('hidden'); $('sendBtn').onclick = sendText;
  } catch (error) { toast(`تعذرت قراءة الملف: ${error.message}`); }
}
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
