// Manual Linux integration smoke test: xvfb-run -a node scripts/android-interop-smoke.cjs
// Android is represented by a Socket.IO client using the production Android handshake.
const { spawn } = require('node:child_process');
const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const io = require(path.join(root, 'node_modules', 'socket.io-client'));
const electron = path.join(root, 'node_modules', '.bin', 'electron');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

function waitForPort(port, timeout = 30000) {
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const poll = () => {
      const socket = net.connect(port, '127.0.0.1');
      socket.once('connect', () => { socket.destroy(); resolve(); });
      socket.once('error', () => {
        socket.destroy();
        if (Date.now() - start > timeout) reject(new Error('Timed out waiting for Windows peer endpoint'));
        else setTimeout(poll, 250);
      });
    };
    poll();
  });
}
function waitFor(socket, event, predicate = () => true, timeout = 7000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { socket.off(event, listener); reject(new Error(`Timed out waiting for ${event}`)); }, timeout);
    const listener = value => {
      if (!predicate(value)) return;
      clearTimeout(timer); socket.off(event, listener); resolve(value);
    };
    socket.on(event, listener);
  });
}
function connect(id, name, platform, local = false) {
  return new Promise((resolve, reject) => {
    const socket = io('http://127.0.0.1:43821', { transports: ['websocket'], reconnection: false, timeout: 5000 });
    socket.once('connect', () => {
      socket.emit('join', { clientId: id, name, platform, local });
      resolve(socket);
    });
    socket.once('connect_error', reject);
  });
}
function ack(socket, event, payload) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${event} acknowledgement timed out`)), 5000);
    socket.emit(event, payload, result => {
      clearTimeout(timer);
      if (result?.ok) resolve(result); else reject(new Error(result?.error || `${event} failed`));
    });
  });
}

(async () => {
  let app;
  const clients = [];
  try {
    app = spawn(electron, ['.', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'], {
      cwd: root, detached: true, stdio: 'inherit', env: { ...process.env, ELECTRON_DISABLE_SECURITY_WARNINGS: '1' }
    });
    await waitForPort(43821);
    await delay(1200);

    const desktopId = 'desktop-test-identity-0001';
    const androidId = 'android-test-identity-001';
    const thirdId = 'peer-test-identity-000001';
    const desktop = await connect(desktopId, 'كمبيوتر الاختبار', 'windows', true); clients.push(desktop);
    await waitFor(desktop, 'identity', value => value.clientId === desktopId);
    const androidContact = waitFor(desktop, 'contacts', list => (list || []).some(item => item.clientId === androidId && item.platform === 'android' && item.online));
    const android = await connect(androidId, 'هاتف الاختبار', 'android', false); clients.push(android);
    await waitFor(android, 'peer-profile', value => value.clientId === desktopId);
    await androidContact;
    const third = await connect(thirdId, 'نظير ثالث', 'windows', false); clients.push(third);
    await waitFor(third, 'identity', value => value.clientId === thirdId);

    const relayed = [];
    third.on('chat-message', message => relayed.push(message));
    const incomingId = crypto.randomUUID();
    const androidToDesktop = waitFor(desktop, 'chat-message', message => message.id === incomingId);
    android.emit('chat', { id: incomingId, channel: 'direct', toId: desktopId, text: 'اختبار مباشر من Android' });
    const incoming = await androidToDesktop;
    if (incoming.senderClientId !== androidId) throw new Error('Android sender identity was not preserved');
    await delay(300);
    if (relayed.some(message => message.id === incomingId)) throw new Error('A received Android message was relayed to a third peer');

    const replyId = crypto.randomUUID();
    const androidReply = waitFor(android, 'chat-message', message => message.id === replyId);
    desktop.emit('chat', { id: replyId, channel: 'direct', toId: androidId, text: 'رد مباشر من Windows' });
    const reply = await androidReply;
    if (reply.senderClientId !== desktopId || reply.toId !== androidId) throw new Error('Direct Windows-to-Android reply was not delivered');

    const incomingSignal = waitFor(desktop, 'signal', packet => packet.from === androidId);
    android.emit('signal', { toClientId: desktopId, description: { type: 'call-start', mode: 'audio' } });
    await incomingSignal;
    const outgoingSignal = waitFor(android, 'signal', packet => packet.from === desktopId);
    desktop.emit('signal', { toClientId: androidId, description: { type: 'answer' } });
    await outgoingSignal;

    const bytes = Buffer.from('Fursan LAN Android direct-file test');
    const transferId = crypto.randomUUID();
    const fileMessage = waitFor(android, 'file-message', message => message.name === 'android-test.txt');
    const init = await ack(desktop, 'upload-init', { transferId, name: 'android-test.txt', mime: 'text/plain', size: bytes.length, channel: 'direct', toId: androidId });
    await ack(desktop, 'upload-chunk', { transferId, offset: init.offset, data: bytes.toString('base64') });
    const completed = await ack(desktop, 'upload-complete', { transferId });
    const file = await fileMessage;
    if (file.id !== completed.fileId) throw new Error('Android received mismatched file metadata');
    const response = await fetch(`http://127.0.0.1:43821/api/files/${file.id}`);
    const downloaded = Buffer.from(await response.arrayBuffer());
    if (!response.ok || !downloaded.equals(bytes)) throw new Error('Direct file download did not match uploaded bytes');

    console.log('PASS: Android message/reply, no third-peer relay, signaling in both directions, and direct file delivery/download.');
  } finally {
    for (const client of clients) client.disconnect();
    if (app?.pid) app.kill('SIGTERM');
    await delay(1000);
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
