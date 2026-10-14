const { app, BrowserWindow, ipcMain, session } = require('electron');
const path = require('node:path');
const os = require('node:os');
const dgram = require('node:dgram');
const http = require('node:http');
const express = require('express');
const { Server } = require('socket.io');
const { RoomStore } = require('./room-store');

const DISCOVERY_PORT = 43820;
const ROOM_PORT = 43821;
const PROTOCOL = 'wasel-lan-v1';
const MAX_MESSAGE_CHARS = 5000;
let win, discovery, roomServer, io, roomStore, roomStarted = false, roomStarting = null, activeCall = null;
const peers = new Map();

const hasInstanceLock = app.requestSingleInstanceLock();
if (!hasInstanceLock) app.quit();
else app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });

function lanAddresses() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) for (const n of list || [])
    if (n.family === 'IPv4' && !n.internal) out.push(n.address);
  return [...new Set(out)];
}
function broadcastRoom() {
  if (!discovery || !roomStarted) return;
  const packet = Buffer.from(JSON.stringify({ protocol: PROTOCOL, port: ROOM_PORT, name: os.hostname() }));
  discovery.send(packet, DISCOVERY_PORT, '255.255.255.255', () => {});
}
function startDiscovery() {
  discovery = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  discovery.on('message', (buf, rinfo) => {
    try {
      const msg = JSON.parse(buf.toString());
      if (msg.protocol === PROTOCOL && Number(msg.port) === ROOM_PORT && !(roomStarted && lanAddresses().includes(rinfo.address))) {
        win?.webContents.send('room-found', { address: rinfo.address, port: ROOM_PORT, name: String(msg.name || 'مضيف محلي').slice(0, 80) });
      }
    } catch (_) {}
  });
  discovery.on('error', error => win?.webContents.send('notice', `تعذر تشغيل اكتشاف الشبكة: ${error.message}`));
  discovery.bind(DISCOVERY_PORT, '0.0.0.0', () => discovery.setBroadcast(true));
  const timer = setInterval(broadcastRoom, 1800);
  timer.unref();
}
function broadcastMembers() {
  if (io) io.emit('members', [...new Map([...peers.values()].map(user => [user.clientId, user])).values()]);
}
function socketForClient(clientId) { return [...peers.entries()].find(([, user]) => user.clientId === clientId)?.[0] || null; }
function broadcastContacts() {
  if (!io || !roomStore) return;
  const contacts = roomStore.contacts.map(contact => ({ ...contact, online: Boolean(socketForClient(contact.clientId)) }));
  for (const id of peers.keys()) io.to(id).emit('contacts', contacts);
}
function publishMessage(message) {
  if (message.channel === 'general') io.emit('chat-message', message);
  else if (message.groupId) {
    const group = roomStore.groupById(message.groupId);
    for (const clientId of group?.members || []) { const id = socketForClient(clientId); if (id) io.to(id).emit('chat-message', message); }
  } else {
    for (const clientId of roomStore.messageRecipients(message)) { const id = socketForClient(clientId); if (id) io.to(id).emit('chat-message', message); }
    const senderSocket = socketForClient(message.senderClientId); if (senderSocket) io.to(senderSocket).emit('chat-message', message);
  }
}
function withinRate(socket, key, limit) {
  const now = Date.now();
  socket.data.rateWindows ||= new Map();
  const recent = (socket.data.rateWindows.get(key) || []).filter(time => now - time < 60_000);
  if (recent.length >= limit) return false;
  recent.push(now); socket.data.rateWindows.set(key, recent); return true;
}
function createRoomServer() {
  const web = express();
  web.get('/api/files/:id', (req, res) => {
    const target = roomStore?.filePath(req.params.id);
    const record = roomStore?.history.find(item => item.type === 'file' && item.id === req.params.id);
    if (!target || !record) return res.status(404).send('الملف غير موجود.');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    if (req.query.preview === '1' && /^image\/(png|jpeg|gif|webp|avif)$/.test(record.mime)) {
      res.type(record.mime);
      return res.sendFile(target, error => { if (error && !res.headersSent) res.status(500).end('تعذر عرض الصورة.'); });
    }
    res.setHeader('Content-Type', 'application/octet-stream');
    res.download(target, record.name, error => { if (error && !res.headersSent) res.status(500).end('تعذر تنزيل الملف.'); });
  });
  roomServer = http.createServer(web);
  io = new Server(roomServer, { maxHttpBufferSize: 15 * 1024 * 1024, cors: { origin: '*' }, connectTimeout: 10000 });
  io.on('connection', socket => {
    socket.on('join', (profile = {}) => {
      if (socket.data.user) return;
      const name = String(profile.name || 'مستخدم').replace(/[\r\n\0]/g, ' ').trim().slice(0, 40) || 'مستخدم';
      const clientId = String(profile.clientId || '').slice(0, 80);
      if (!/^[a-zA-Z0-9_-]{16,80}$/.test(clientId)) return socket.emit('notice', 'تعذرت قراءة هوية هذا الجهاز؛ أعد تشغيل التطبيق.');
      const user = { id: socket.id, clientId, name, joinedAt: Date.now() };
      peers.set(socket.id, user);
      socket.data.user = user;
      roomStore.rememberContact(user);
      broadcastContacts();
      socket.emit('identity', { clientId });
      socket.emit('history', roomStore.historyFor(clientId));
      socket.emit('groups', roomStore.visibleGroups(clientId));
      socket.emit('contacts', roomStore.contacts.map(contact => ({ ...contact, online: Boolean(socketForClient(contact.clientId)) })));
      for (const message of roomStore.offlineFor(clientId)) {
        roomStore.markDelivered(message.id, clientId);
        socket.emit('chat-message', message);
        const from = socketForClient(message.senderClientId);
        if (from) io.to(from).emit('message-receipt', { id: message.id, kind: 'delivered', clientId });
      }
      broadcastMembers();
      io.emit('system-message', { text: `${user.name} انضم إلى الغرفة`, time: Date.now() });
      if (activeCall) io.to(socket.id).emit('signal', { from: activeCall.owner, description: { type: 'call-start', mode: activeCall.mode } });
    });
    socket.on('chat', raw => {
      if (!socket.data.user) return;
      if (!withinRate(socket, 'chat', 40)) return socket.emit('notice', 'أرسلت رسائل بسرعة كبيرة؛ انتظر قليلاً ثم حاول مجدداً.');
      const text = String(raw?.text || '').trim().slice(0, MAX_MESSAGE_CHARS);
      if (!text) return;
      const channel = raw?.channel === 'general' ? 'general' : 'direct';
      const toId = channel === 'direct' ? String(raw?.toId || '').slice(0, 80) : undefined;
      const groupId = channel === 'direct' && raw?.groupId ? String(raw.groupId).slice(0, 80) : undefined;
      const group = groupId ? roomStore.groupById(groupId) : null;
      if (groupId && !group?.members.includes(socket.data.user.clientId)) return socket.emit('notice', 'لا تملك صلاحية إرسال رسالة إلى هذه المجموعة.');
      if (!groupId && channel === 'direct' && (!toId || toId === socket.data.user.clientId)) return socket.emit('notice', 'اختر مستخدماً آخر للمحادثة الخاصة.');
      if (!groupId && channel === 'direct' && !roomStore.contacts.some(contact => contact.clientId === toId)) return socket.emit('notice', 'المستخدم المحدد غير معروف للمضيف.');
      const message = { id: require('node:crypto').randomUUID(), type: 'text', text, user: socket.data.user.name, senderId: socket.id, senderClientId: socket.data.user.clientId, channel, toId: groupId ? undefined : toId, groupId, recipientIds: channel === 'general' ? [...new Set([...roomStore.contacts.map(c => c.clientId), socket.data.user.clientId])] : undefined, time: Date.now(), deliveredTo: [], readBy: [] };
      try {
        roomStore.addMessage(message); publishMessage(message);
        for (const clientId of roomStore.messageRecipients(message)) {
          if (socketForClient(clientId)) { roomStore.markDelivered(message.id, clientId); const recipientSocket = socketForClient(clientId); io.to(recipientSocket).emit('message-receipt', { id: message.id, kind: 'delivered', clientId }); }
        }
      }
      catch (error) { socket.emit('notice', `تعذر حفظ الرسالة: ${error.message}`); }
    });
    socket.on('broadcast', raw => {
      if (!socket.data.user || !withinRate(socket, 'chat', 40)) return;
      const text = String(raw?.text || '').trim().slice(0, MAX_MESSAGE_CHARS);
      if (!text) return;
      const recipientIds = roomStore.contacts.map(contact => contact.clientId).filter(id => id !== socket.data.user.clientId);
      const message = { id: require('node:crypto').randomUUID(), type: 'text', text, user: socket.data.user.name, senderId: socket.id, senderClientId: socket.data.user.clientId, channel: 'broadcast', recipientIds, time: Date.now(), deliveredTo: [], readBy: [] };
      try { roomStore.addMessage(message); publishMessage(message); socket.emit('chat-message', message); }
      catch (error) { socket.emit('notice', `تعذر حفظ رسالة البث: ${error.message}`); }
    });
    socket.on('create-group', raw => {
      if (!socket.data.user || !withinRate(socket, 'group', 10)) return;
      try {
        const members = (Array.isArray(raw?.members) ? raw.members : []).filter(id => roomStore.contacts.some(contact => contact.clientId === id));
        const group = roomStore.createGroup({ name: raw?.name, members, ownerId: socket.data.user.clientId });
        for (const clientId of group.members) { const id = socketForClient(clientId); if (id) io.to(id).emit('groups', roomStore.visibleGroups(clientId)); }
        socket.emit('notice', `تم إنشاء مجموعة «${group.name}».`);
      } catch (error) { socket.emit('notice', error.message); }
    });
    socket.on('mark-read', raw => {
      if (!socket.data.user) return;
      const message = roomStore.markRead(String(raw?.id || ''), socket.data.user.clientId);
      if (!message) return;
      const from = socketForClient(message.senderClientId);
      if (from) io.to(from).emit('message-receipt', { id: message.id, kind: 'read', clientId: socket.data.user.clientId });
    });
    socket.on('upload-init', (raw, acknowledge = () => {}) => {
      if (!socket.data.user) return acknowledge({ ok: false, error: 'لم تنضم إلى الغرفة.' });
      try {
        const group = raw?.groupId ? roomStore.groupById(String(raw.groupId)) : null;
        if (raw?.groupId && !group?.members.includes(socket.data.user.clientId)) throw new Error('لا تملك صلاحية المشاركة في هذه المجموعة.');
        const channel = raw?.channel === 'general' ? 'general' : 'direct';
        const toId = channel === 'direct' && !raw?.groupId ? String(raw?.toId || '') : undefined;
        if (channel === 'direct' && !raw?.groupId && !roomStore.contacts.some(contact => contact.clientId === toId)) throw new Error('المستلم غير معروف للمضيف.');
        const offset = roomStore.initUpload({ ...raw, channel, toId, user: socket.data.user.name, senderId: socket.id, senderClientId: socket.data.user.clientId });
        acknowledge({ ok: true, offset });
      } catch (error) { acknowledge({ ok: false, error: error.message }); }
    });
    socket.on('upload-chunk', (raw, acknowledge = () => {}) => {
      if (!socket.data.user || !withinRate(socket, 'upload-chunk', 1000)) return acknowledge({ ok: false, error: 'الرفع سريع جداً أو لم تنضم إلى الغرفة.' });
        try { acknowledge({ ok: true, offset: roomStore.appendUploadChunk(raw.transferId, raw.offset, raw.data, socket.data.user.clientId) }); }
      catch (error) { acknowledge({ ok: false, error: error.message }); }
    });
    socket.on('upload-complete', (raw, acknowledge = () => {}) => {
      if (!socket.data.user) return acknowledge({ ok: false, error: 'لم تنضم إلى الغرفة.' });
      try {
        const record = roomStore.finishUpload(String(raw?.transferId || ''), socket.data.user.clientId);
        const { diskName, ...message } = record;
        const group = message.groupId ? roomStore.groupById(message.groupId) : null;
        const targets = message.channel === 'general' ? [...peers.keys()] : message.groupId ? (group?.members || []).map(socketForClient).filter(Boolean) : [socketForClient(message.toId), socket.id].filter(Boolean);
        for (const id of targets) io.to(id).emit('file-message', message);
        acknowledge({ ok: true, fileId: message.id });
      } catch (error) { acknowledge({ ok: false, error: error.message }); }
    });
    socket.on('upload-cancel', raw => { if (socket.data.user) roomStore.cancelUpload(String(raw?.transferId || ''), socket.data.user.clientId); });
    socket.on('file', payload => {
      if (!socket.data.user) return;
      if (!withinRate(socket, 'file', 8)) return socket.emit('notice', 'بلغت حد مشاركة الملفات مؤقتاً؛ حاول لاحقاً.');
      try {
        const group = payload?.groupId ? roomStore.groupById(String(payload.groupId)) : null;
        if (payload?.groupId && !group?.members.includes(socket.data.user.clientId)) throw new Error('لا تملك صلاحية مشاركة ملف في هذه المجموعة.');
        const channel = payload?.channel === 'general' ? 'general' : 'direct';
        const toId = channel === 'direct' && !payload?.groupId ? String(payload?.toId || '') : undefined;
        if (channel === 'direct' && !payload?.groupId && !roomStore.contacts.some(contact => contact.clientId === toId)) throw new Error('اختر مستلماً معروفاً للمضيف.');
        const record = roomStore.addFile({ ...payload, channel, toId, groupId: payload?.groupId, user: socket.data.user.name, senderId: socket.id, senderClientId: socket.data.user.clientId, time: Date.now() });
        const { diskName, ...message } = record;
        const targets = message.channel === 'general' ? [...peers.keys()] : message.groupId ? (group?.members || []).map(socketForClient).filter(Boolean) : [socketForClient(message.toId), socket.id].filter(Boolean);
        for (const id of targets) io.to(id).emit('file-message', message);
      } catch (error) { socket.emit('notice', error.message || 'تعذر حفظ الملف.'); }
    });
    socket.on('signal', packet => {
      if (!socket.data.user || !packet) return;
      if (!withinRate(socket, 'signal', 180)) return;
      if (packet.description?.type === 'call-start') {
        activeCall = { owner: socket.id, mode: packet.description.mode === 'video' ? 'video' : 'audio' };
        socket.broadcast.emit('signal', { from: socket.id, description: { type: 'call-start', mode: activeCall.mode } });
      } else if (packet.description?.type === 'call-end') {
        activeCall = null;
        socket.broadcast.emit('signal', { from: socket.id, description: { type: 'call-end' } });
      } else if (typeof packet.to === 'string' && peers.has(packet.to)) {
        io.to(packet.to).emit('signal', { from: socket.id, description: packet.description, candidate: packet.candidate });
      }
    });
    socket.on('disconnect', () => {
      const user = peers.get(socket.id); peers.delete(socket.id);
      if (user) {
        if (activeCall?.owner === socket.id) { activeCall = null; socket.broadcast.emit('signal', { from: socket.id, description: { type: 'call-end' } }); }
        broadcastMembers(); broadcastContacts(); io.emit('system-message', { text: `${user.name} غادر الغرفة`, time: Date.now() });
      }
    });
  });
}
async function startRoom() {
  if (roomStarted) return { ok: true, addresses: lanAddresses(), port: ROOM_PORT };
  if (roomStarting) return roomStarting;
  roomStarting = new Promise(resolve => {
    roomStore = new RoomStore(path.join(app.getPath('userData'), 'wasel-room'));
    createRoomServer();
    const onError = error => {
      roomStarted = false;
      resolve({ ok: false, error: error.code === 'EADDRINUSE' ? 'المنفذ 43821 مستخدم بالفعل. أغلق نسخة واصل الأخرى على هذا الجهاز.' : error.message });
    };
    roomServer.once('error', onError);
    roomServer.listen(ROOM_PORT, '0.0.0.0', () => {
      roomServer.removeListener('error', onError);
      roomStarted = true;
      broadcastRoom();
      resolve({ ok: true, addresses: lanAddresses(), port: ROOM_PORT });
      win?.webContents.send('room-started', { addresses: lanAddresses(), port: ROOM_PORT });
    });
  }).finally(() => { roomStarting = null; });
  return roomStarting;
}
function makeWindow() {
  win = new BrowserWindow({ width: 1280, height: 820, minWidth: 900, minHeight: 620, backgroundColor: '#f4f7f7', title: 'واصل - تواصل المنشأة', webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true } });
  win.loadFile(path.join(__dirname, 'index.html'));
}
app.whenReady().then(() => {
  if (!hasInstanceLock) return;
  app.setAppUserModelId('com.wasel.lan');
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => callback(['media', 'camera', 'microphone'].includes(permission)));
  startDiscovery(); makeWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) makeWindow(); });
});
ipcMain.handle('start-room', () => startRoom());
ipcMain.handle('lan-info', () => ({ addresses: lanAddresses(), hostname: os.hostname(), port: ROOM_PORT }));
app.on('before-quit', () => { try { discovery?.close(); io?.close(); roomServer?.close(); } catch (_) {} });
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
