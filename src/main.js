const { app, BrowserWindow, ipcMain, session } = require('electron');
const path = require('node:path');
const os = require('node:os');
const dgram = require('node:dgram');
const http = require('node:http');
const express = require('express');
const { Server } = require('socket.io');

const DISCOVERY_PORT = 43820;
const ROOM_PORT = 43821;
const PROTOCOL = 'wasel-lan-v1';
let win, discovery, roomServer, io, roomStarted = false;

function lanAddresses() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) for (const n of list || [])
    if (n.family === 'IPv4' && !n.internal) out.push(n.address);
  return out;
}
function broadcastRoom() {
  if (!discovery || !roomStarted) return;
  const packet = Buffer.from(JSON.stringify({ protocol: PROTOCOL, port: ROOM_PORT, name: os.hostname() }));
  discovery.setBroadcast(true);
  discovery.send(packet, DISCOVERY_PORT, '255.255.255.255', () => {});
}
function startDiscovery() {
  discovery = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  discovery.on('message', (buf, rinfo) => {
    try {
      const msg = JSON.parse(buf.toString());
      if (msg.protocol === PROTOCOL && msg.port === ROOM_PORT) {
        win?.webContents.send('room-found', { address: rinfo.address, port: msg.port, name: msg.name });
      }
    } catch (_) {}
  });
  discovery.on('error', () => {});
  discovery.bind(DISCOVERY_PORT, '0.0.0.0', () => discovery.setBroadcast(true));
  setInterval(broadcastRoom, 1800).unref();
}
function startRoom() {
  if (roomStarted) return { ok: true, addresses: lanAddresses(), port: ROOM_PORT };
  const web = express();
  roomServer = http.createServer(web);
  io = new Server(roomServer, { maxHttpBufferSize: 20 * 1024 * 1024, cors: { origin: '*' } });
  const peers = new Map();
  const history = [];
  io.on('connection', socket => {
    socket.on('join', (profile = {}) => {
      const user = { id: socket.id, name: String(profile.name || 'مستخدم').slice(0, 40), joinedAt: Date.now() };
      peers.set(socket.id, user);
      socket.data.user = user;
      socket.emit('history', history.slice(-200));
      io.emit('members', [...peers.values()]);
      io.emit('system-message', { text: `${user.name} انضم إلى الغرفة`, time: Date.now() });
    });
    socket.on('chat', raw => {
      const text = String(raw?.text || '').slice(0, 5000).trim();
      if (!text || !socket.data.user) return;
      const msg = { id: `${Date.now()}-${socket.id}`, type: 'text', text, user: socket.data.user.name, senderId: socket.id, time: Date.now() };
      history.push(msg); if (history.length > 500) history.shift(); io.emit('chat-message', msg);
    });
    socket.on('file', payload => {
      if (!socket.data.user || !payload || typeof payload.data !== 'string') return;
      if (payload.data.length > 15 * 1024 * 1024) return socket.emit('notice', 'الحد الأقصى للملف 10 ميغابايت في النسخة التجريبية.');
      io.emit('file-message', { name: String(payload.name || 'ملف').slice(0, 180), mime: String(payload.mime || 'application/octet-stream'), data: payload.data, user: socket.data.user.name, senderId: socket.id, time: Date.now() });
    });
    socket.on('signal', packet => {
      if (packet?.description?.type === 'call-start') {
        socket.broadcast.emit('signal', { from: socket.id, description: packet.description });
      } else if (packet && typeof packet.to === 'string') io.to(packet.to).emit('signal', { from: socket.id, description: packet.description, candidate: packet.candidate });
    });
    socket.on('disconnect', () => {
      const user = peers.get(socket.id); peers.delete(socket.id);
      if (user) { io.emit('members', [...peers.values()]); io.emit('system-message', { text: `${user.name} غادر الغرفة`, time: Date.now() }); }
    });
  });
  roomServer.on('error', e => win?.webContents.send('notice', e.message));
  roomServer.listen(ROOM_PORT, '0.0.0.0', () => {
    roomStarted = true; broadcastRoom();
    win?.webContents.send('room-started', { addresses: lanAddresses(), port: ROOM_PORT });
  });
  return { ok: true, addresses: lanAddresses(), port: ROOM_PORT };
}
function makeWindow() {
  win = new BrowserWindow({ width: 1280, height: 820, minWidth: 900, minHeight: 620, backgroundColor: '#f4f7f7', title: 'واصل - تواصل المنشأة', webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true } });
  win.loadFile(path.join(__dirname, 'index.html'));
}
app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => callback(['media', 'camera', 'microphone'].includes(permission)));
  startDiscovery(); makeWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) makeWindow(); });
});
ipcMain.handle('start-room', () => startRoom());
ipcMain.handle('lan-info', () => ({ addresses: lanAddresses(), hostname: os.hostname(), port: ROOM_PORT }));
app.on('before-quit', () => { try { discovery?.close(); io?.close(); roomServer?.close(); } catch (_) {} });
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
