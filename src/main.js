const { app, BrowserWindow, ipcMain, session } = require('electron');
const path = require('node:path');
const os = require('node:os');
const dgram = require('node:dgram');
const http = require('node:http');
const express = require('express');
const { Server } = require('socket.io');
const { RoomStore } = require('./room-store');

const DISCOVERY_PORT = 43820;
const PEER_PORT = 43821;
const PROTOCOL = 'fursan-p2p-v1';
const MAX_MESSAGE_CHARS = 5000;
let win, discovery, peerServer, io, roomStore, peerStarted = false, peerStarting = null, activeCall = null;
let localClientId = null, localProfile = null;
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
  if (!discovery || !peerStarted) return;
  const packet = Buffer.from(JSON.stringify({ protocol: PROTOCOL, port: PEER_PORT, name: os.hostname() }));
  discovery.send(packet, DISCOVERY_PORT, '255.255.255.255', () => {});
}
function startDiscovery() {
  discovery = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  discovery.on('message', (buf, rinfo) => {
    try {
      const msg = JSON.parse(buf.toString());
      if (msg.protocol === PROTOCOL && Number(msg.port) === PEER_PORT && !(peerStarted && lanAddresses().includes(rinfo.address))) {
        win?.webContents.send('peer-found', { address: rinfo.address, port: PEER_PORT, name: String(msg.name || 'نظير محلي').slice(0, 80) });
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
  const contacts = roomStore.contacts.map(contact => ({ ...contact, platform: contact.platform || 'windows', online: Boolean(socketForClient(contact.clientId)) }));
  for (const id of peers.keys()) io.to(id).emit('contacts', contacts);
}
function deliverToConnectedAndroid(event, message) {
  if (!io || !roomStore || message.senderClientId !== localClientId) return;
  const localSocket = socketForClient(localClientId);
  for (const clientId of roomStore.messageRecipients(message)) {
    const socketId = socketForClient(clientId);
    if (!socketId || socketId === localSocket || peers.get(socketId)?.platform !== 'android') continue;
    io.to(socketId).emit(event, message);
  }
}
function publishMessage(message) {
  // A local Windows sender may deliver directly to its already-connected Android
  // recipient. Payloads received from another peer are never relayed onward.
  const localSocket = localClientId ? socketForClient(localClientId) : null;
  if (localSocket) io.to(localSocket).emit('chat-message', message);
  deliverToConnectedAndroid('chat-message', message);
}
function withinRate(socket, key, limit) {
  const now = Date.now();
  socket.data.rateWindows ||= new Map();
  const recent = (socket.data.rateWindows.get(key) || []).filter(time => now - time < 60_000);
  if (recent.length >= limit) return false;
  recent.push(now); socket.data.rateWindows.set(key, recent); return true;
}
function createPeerEndpoint() {
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
  peerServer = http.createServer(web);
  io = new Server(peerServer, { maxHttpBufferSize: 15 * 1024 * 1024, cors: { origin: '*' }, connectTimeout: 10000 });
  io.on('connection', socket => {
    socket.on('join', (profile = {}) => {
      if (socket.data.user) return;
      const name = String(profile.name || 'مستخدم').replace(/[\r\n\0]/g, ' ').trim().slice(0, 40) || 'مستخدم';
      const clientId = String(profile.clientId || '').slice(0, 80);
      if (!/^[a-zA-Z0-9_-]{16,80}$/.test(clientId)) return socket.emit('notice', 'تعذرت قراءة هوية هذا الجهاز؛ أعد تشغيل التطبيق.');
      const platform = profile.platform === 'android' ? 'android' : 'windows';
      const user = { id: socket.id, clientId, name, platform, joinedAt: Date.now() };
      const remoteAddress = String(socket.handshake.address || '');
      const trustedLocal = profile.local === true && ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(remoteAddress);
      if (trustedLocal) { localClientId = clientId; localProfile = { clientId, name, platform: 'windows' }; }
      peers.set(socket.id, user);
      socket.data.user = user;
      roomStore.rememberContact(user);
      broadcastContacts();
      socket.emit('identity', { clientId });
      if (localProfile) socket.emit('peer-profile', localProfile);
      if (trustedLocal) for (const id of peers.keys()) if (id !== socket.id) io.to(id).emit('peer-profile', localProfile);
      socket.emit('history', roomStore.historyFor(clientId));
      socket.emit('groups', roomStore.visibleGroups(clientId));
      socket.emit('contacts', roomStore.contacts.map(contact => ({ ...contact, platform: contact.platform || 'windows', online: Boolean(socketForClient(contact.clientId)) })));
      for (const message of roomStore.offlineFor(clientId)) {
        roomStore.markDelivered(message.id, clientId);
        socket.emit('chat-message', message);
        const from = socketForClient(message.senderClientId);
        if (from) io.to(from).emit('message-receipt', { id: message.id, kind: 'delivered', clientId });
      }
      broadcastMembers();
      io.emit('system-message', { text: `${user.name} اتصل بنظير محلي`, time: Date.now() });
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
      if (!groupId && channel === 'direct' && !/^[a-zA-Z0-9_-]{16,80}$/.test(toId)) return socket.emit('notice', 'معرف النظير المستهدف غير صالح.');
      const messageId = /^[0-9a-f-]{36}$/i.test(String(raw?.id || '')) ? raw.id : require('node:crypto').randomUUID();
      const message = { id: messageId, type: 'text', text, user: socket.data.user.name, senderId: socket.id, senderClientId: socket.data.user.clientId, channel, toId: groupId ? undefined : toId, groupId, recipientIds: channel === 'general' ? [...new Set([...(Array.isArray(raw?.recipientIds) ? raw.recipientIds.filter(id => /^[a-zA-Z0-9_-]{16,80}$/.test(id)) : roomStore.contacts.map(c => c.clientId)), socket.data.user.clientId])] : undefined, time: Date.now(), deliveredTo: [], readBy: [] };
      try {
        roomStore.addMessage(message); publishMessage(message);
        for (const clientId of roomStore.messageRecipients(message)) {
          if (socketForClient(clientId)) { roomStore.markDelivered(message.id, clientId); const senderSocket = socketForClient(message.senderClientId); if (senderSocket && clientId !== message.senderClientId) io.to(senderSocket).emit('message-receipt', { id: message.id, kind: 'delivered', clientId }); }
        }
      }
      catch (error) { socket.emit('notice', `تعذر حفظ الرسالة: ${error.message}`); }
    });
    socket.on('broadcast', raw => {
      if (!socket.data.user || !withinRate(socket, 'chat', 40)) return;
      const text = String(raw?.text || '').trim().slice(0, MAX_MESSAGE_CHARS);
      if (!text) return;
      const recipientIds = roomStore.contacts.map(contact => contact.clientId).filter(id => id !== socket.data.user.clientId);
      const messageId = /^[0-9a-f-]{36}$/i.test(String(raw?.id || '')) ? raw.id : require('node:crypto').randomUUID();
      const message = { id: messageId, type: 'text', text, user: socket.data.user.name, senderId: socket.id, senderClientId: socket.data.user.clientId, channel: 'broadcast', recipientIds, time: Date.now(), deliveredTo: [], readBy: [] };
      try { roomStore.addMessage(message); publishMessage(message); }
      catch (error) { socket.emit('notice', `تعذر حفظ رسالة البث: ${error.message}`); }
    });
    socket.on('create-group', raw => {
      if (!socket.data.user || !withinRate(socket, 'group', 10)) return;
      try {
        const members = [...new Set((Array.isArray(raw?.members) ? raw.members : []).filter(id => /^[a-zA-Z0-9_-]{16,80}$/.test(id)))];
        const group = roomStore.createGroup({ id: raw?.id, name: raw?.name, members, ownerId: raw?.ownerId || socket.data.user.clientId });
        for (const clientId of group.members) { const id = socketForClient(clientId); if (id) io.to(id).emit('groups', roomStore.visibleGroups(clientId)); }
        socket.emit('notice', `تم إنشاء مجموعة «${group.name}».`);
      } catch (error) { socket.emit('notice', error.message); }
    });
    socket.on('cache-message', raw => {
      if (!socket.data.user || socket.data.user.clientId !== localClientId || socket.id !== socketForClient(localClientId)) return;
      if (!raw || raw.toId !== localClientId || !/^[0-9a-f-]{36}$/i.test(String(raw.id || '')) || roomStore.history.some(item => item.id === raw.id)) return;
      try { roomStore.addMessage({ ...raw, channel: 'direct', type: 'text', deliveredTo: [...new Set([...(raw.deliveredTo || []), localClientId])] }); publishMessage(raw); } catch (_) {}
    });
    socket.on('mark-read', raw => {
      if (!socket.data.user) return;
      const message = roomStore.markRead(String(raw?.id || ''), socket.data.user.clientId);
      if (!message) return;
      const from = socketForClient(message.senderClientId);
      if (from) io.to(from).emit('message-receipt', { id: message.id, kind: 'read', clientId: socket.data.user.clientId });
    });
    socket.on('upload-init', (raw, acknowledge = () => {}) => {
      if (!socket.data.user) return acknowledge({ ok: false, error: 'لم تتصل بنقطة النظير.' });
      try {
        const group = raw?.groupId ? roomStore.groupById(String(raw.groupId)) : null;
        if (raw?.groupId && !group?.members.includes(socket.data.user.clientId)) throw new Error('لا تملك صلاحية المشاركة في هذه المجموعة.');
        const channel = raw?.channel === 'general' ? 'general' : 'direct';
        const toId = channel === 'direct' && !raw?.groupId ? String(raw?.toId || '') : undefined;
        if (channel === 'direct' && !raw?.groupId && !/^[a-zA-Z0-9_-]{16,80}$/.test(toId)) throw new Error('معرف النظير المستهدف غير صالح.');
        const recipientIds = channel === 'general'
          ? [...new Set((Array.isArray(raw?.recipientIds) ? raw.recipientIds : roomStore.contacts.map(contact => contact.clientId)).filter(id => /^[a-zA-Z0-9_-]{16,80}$/.test(id)))]
          : undefined;
        const offset = roomStore.initUpload({ ...raw, channel, toId, recipientIds, user: socket.data.user.name, senderId: socket.id, senderClientId: socket.data.user.clientId });
        acknowledge({ ok: true, offset });
      } catch (error) { acknowledge({ ok: false, error: error.message }); }
    });
    socket.on('upload-chunk', (raw, acknowledge = () => {}) => {
      if (!socket.data.user || !withinRate(socket, 'upload-chunk', 1000)) return acknowledge({ ok: false, error: 'الرفع سريع جداً أو لم تتصل بنقطة النظير.' });
        try { acknowledge({ ok: true, offset: roomStore.appendUploadChunk(raw.transferId, raw.offset, raw.data, socket.data.user.clientId) }); }
      catch (error) { acknowledge({ ok: false, error: error.message }); }
    });
    socket.on('upload-complete', (raw, acknowledge = () => {}) => {
      if (!socket.data.user) return acknowledge({ ok: false, error: 'لم تتصل بنقطة النظير.' });
      try {
        const record = roomStore.finishUpload(String(raw?.transferId || ''), socket.data.user.clientId);
        const { diskName, ...message } = record;
        const group = message.groupId ? roomStore.groupById(message.groupId) : null;
        const localSocket = localClientId ? socketForClient(localClientId) : null;
        const belongsHere = message.senderClientId === localClientId || message.channel === 'general' || (message.groupId && group?.members.includes(localClientId)) || message.toId === localClientId;
        if (localSocket && belongsHere) io.to(localSocket).emit('file-message', message);
        deliverToConnectedAndroid('file-message', message);
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
        if (channel === 'direct' && !payload?.groupId && !/^[a-zA-Z0-9_-]{16,80}$/.test(toId)) throw new Error('معرف النظير المستهدف غير صالح.');
        const record = roomStore.addFile({ ...payload, channel, toId, groupId: payload?.groupId, user: socket.data.user.name, senderId: socket.id, senderClientId: socket.data.user.clientId, time: Date.now() });
        const { diskName, ...message } = record;
        const localSocket = localClientId ? socketForClient(localClientId) : null;
        if (localSocket) io.to(localSocket).emit('file-message', message);
        deliverToConnectedAndroid('file-message', message);
      } catch (error) { socket.emit('notice', error.message || 'تعذر حفظ الملف.'); }
    });
    socket.on('signal', packet => {
      if (!socket.data.user || !packet) return;
      if (!withinRate(socket, 'signal', 180)) return;
      const localSocket = localClientId ? socketForClient(localClientId) : null;
      const senderId = socket.data.user.clientId;
      const targetId = String(packet.toClientId || '');
      if (senderId === localClientId && socket.id === localSocket && targetId) {
        const targetSocket = socketForClient(targetId);
        if (targetSocket && peers.get(targetSocket)?.platform === 'android') io.to(targetSocket).emit('signal', { from: localClientId, description: packet.description, candidate: packet.candidate });
        return;
      }
      if (localSocket && senderId !== localClientId && (!targetId || targetId === localClientId)) {
        io.to(localSocket).emit('signal', { from: senderId, description: packet.description, candidate: packet.candidate });
      }
    });
    socket.on('disconnect', () => {
      const user = peers.get(socket.id); peers.delete(socket.id);
      if (user) {
        if (activeCall?.owner === socket.id) activeCall = null;
        broadcastMembers(); broadcastContacts(); io.emit('system-message', { text: `${user.name} انقطع اتصال`, time: Date.now() });
      }
    });
  });
}
async function startPeer() {
  if (peerStarted) return { ok: true, addresses: lanAddresses(), port: PEER_PORT };
  if (peerStarting) return peerStarting;
  peerStarting = new Promise(resolve => {
    roomStore = new RoomStore(path.join(app.getPath('userData'), 'wasel-room'));

    createPeerEndpoint();
    const onError = error => {
      peerStarted = false;
      resolve({ ok: false, error: error.code === 'EADDRINUSE' ? 'المنفذ 43821 مستخدم بالفعل. أغلق نسخة فرسان لان الأخرى على هذا الجهاز.' : error.message });
    };
    peerServer.once('error', onError);
    peerServer.listen(PEER_PORT, '0.0.0.0', () => {
      peerServer.removeListener('error', onError);
      peerStarted = true;
      broadcastRoom();
      resolve({ ok: true, addresses: lanAddresses(), port: PEER_PORT });
      win?.webContents.send('peer-started', { addresses: lanAddresses(), port: PEER_PORT });
    });
  }).finally(() => { peerStarting = null; });
  return peerStarting;
}
function makeWindow() {
  win = new BrowserWindow({ width: 1280, height: 820, minWidth: 900, minHeight: 620, backgroundColor: '#f4f7f7', title: 'فرسان لان — مراسلة P2P محلية', webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true } });
  win.loadFile(path.join(__dirname, 'index.html'));
}
app.whenReady().then(() => {
  if (!hasInstanceLock) return;
  app.setAppUserModelId('com.fursan.lan');
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => callback(['media', 'camera', 'microphone'].includes(permission)));
  startDiscovery(); makeWindow();
  startPeer().then(result => { if (!result.ok) win?.webContents.send('notice', result.error); });
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) makeWindow(); });
});
ipcMain.handle('start-peer', () => startPeer());
ipcMain.handle('lan-info', () => ({ addresses: lanAddresses(), hostname: os.hostname(), port: PEER_PORT }));
app.on('before-quit', () => { try { discovery?.close(); io?.close(); peerServer?.close(); } catch (_) {} });
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
