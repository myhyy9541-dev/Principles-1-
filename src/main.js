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
function broadcastMembers() { if (io) io.emit('members', [...peers.values()]); }
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
      const user = { id: socket.id, name, joinedAt: Date.now() };
      peers.set(socket.id, user);
      socket.data.user = user;
      socket.emit('history', roomStore.publicHistory());
      broadcastMembers();
      io.emit('system-message', { text: `${user.name} انضم إلى الغرفة`, time: Date.now() });
      if (activeCall) io.to(socket.id).emit('signal', { from: activeCall.owner, description: { type: 'call-start', mode: activeCall.mode } });
    });
    socket.on('chat', raw => {
      if (!socket.data.user) return;
      if (!withinRate(socket, 'chat', 40)) return socket.emit('notice', 'أرسلت رسائل بسرعة كبيرة؛ انتظر قليلاً ثم حاول مجدداً.');
      const text = String(raw?.text || '').trim().slice(0, MAX_MESSAGE_CHARS);
      if (!text) return;
      const message = { id: `${Date.now()}-${socket.id}`, type: 'text', text, user: socket.data.user.name, senderId: socket.id, time: Date.now() };
      try { roomStore.addMessage(message); io.emit('chat-message', message); }
      catch (error) { socket.emit('notice', `تعذر حفظ الرسالة: ${error.message}`); }
    });
    socket.on('file', payload => {
      if (!socket.data.user) return;
      if (!withinRate(socket, 'file', 8)) return socket.emit('notice', 'بلغت حد مشاركة الملفات مؤقتاً؛ حاول لاحقاً.');
      try {
        const record = roomStore.addFile({ ...payload, user: socket.data.user.name, senderId: socket.id, time: Date.now() });
        const { diskName, ...message } = record;
        io.emit('file-message', message);
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
        broadcastMembers(); io.emit('system-message', { text: `${user.name} غادر الغرفة`, time: Date.now() });
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
