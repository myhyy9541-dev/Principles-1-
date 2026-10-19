'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { BluetoothHost } = require('../src/bluetooth-host');

class FakeTransport extends EventEmitter {
  constructor() { super(); this.port = null; this.sent = []; }
  async listPorts() { return [{ path: 'COM3', label: 'SPP' }]; }
  async connect(path) { this.port = { path, isOpen: true }; this.emit('connected', { path }); return { path }; }
  async sendFrame(frame) { if (!this.port?.isOpen) throw new Error('closed'); this.sent.push(frame); }
  async close() { const path = this.port?.path; this.port = null; this.emit('disconnected', { path }); }
}

class FakeSocket extends EventEmitter {
  constructor() { super(); this.connected = false; this.sent = []; }
  serverConnect() { this.connected = true; EventEmitter.prototype.emit.call(this, 'connect'); }
  emit(event, ...args) {
    this.sent.push({ event, args });
    if (typeof args[1] === 'function') args[1]({ ok: true, offset: 16 });
    return true;
  }
  serverEmit(event, ...args) { return EventEmitter.prototype.emit.call(this, event, ...args); }
  disconnect() { this.connected = false; EventEmitter.prototype.emit.call(this, 'disconnect', 'client namespace disconnect'); }
}

function createSocketClient(socket) {
  return () => {
    queueMicrotask(() => socket.serverConnect());
    return socket;
  };
}

const localProfile = { clientId: 'windows-client-123456', name: 'حاسوب', platform: 'windows' };
const remoteProfile = { clientId: 'android-client-123456', name: 'هاتف', platform: 'android' };

test('performs direct RFCOMM hello and joins the local endpoint as the remote device', async () => {
  const transport = new FakeTransport();
  const socket = new FakeSocket();
  const host = new BluetoothHost({ createClient: createSocketClient(socket), peerPort: 43821, transportFactory: () => transport });
  const events = [];
  host.on('event', event => events.push(event));
  host.setLocalProfile(localProfile);
  await host.connect('COM3');
  assert.deepEqual(transport.sent[0], { v: 1, type: 'hello', ...localProfile });
  transport.emit('frame', { v: 1, type: 'hello', ...remoteProfile });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(socket.sent[0].event, 'join');
  assert.deepEqual(socket.sent[0].args[0], { ...remoteProfile, local: false });
  assert.equal(typeof socket.sent[0].args[1], 'function');
  assert.ok(events.some(event => event.type === 'peer-connected' && event.clientId === remoteProfile.clientId));
  await host.close();
});

test('forwards allowed direct messages and acknowledges local endpoint responses', async () => {
  const transport = new FakeTransport();
  const socket = new FakeSocket();
  const host = new BluetoothHost({ createClient: createSocketClient(socket), peerPort: 43821, transportFactory: () => transport });
  host.setLocalProfile(localProfile);
  await host.connect('COM3');
  transport.emit('frame', { v: 1, type: 'hello', ...remoteProfile });
  await new Promise(resolve => setImmediate(resolve));

  transport.emit('frame', { v: 1, type: 'event', id: 'request-1', event: 'upload-chunk', data: { offset: 0 } });
  assert.equal(socket.sent.at(-1).event, 'upload-chunk');
  assert.deepEqual(transport.sent.at(-1), { v: 1, type: 'ack', id: 'request-1', data: { ok: true, offset: 16 } });

  socket.serverEmit('chat-message', { id: 'm1', text: 'مرحباً' });
  assert.deepEqual(transport.sent.at(-1), { v: 1, type: 'event', event: 'chat-message', data: { id: 'm1', text: 'مرحباً' } });
  await host.close();
});

test('rejects signaling over Bluetooth rather than pretending WebRTC media will work', async () => {
  const transport = new FakeTransport();
  const socket = new FakeSocket();
  const host = new BluetoothHost({ createClient: createSocketClient(socket), peerPort: 43821, transportFactory: () => transport });
  host.setLocalProfile(localProfile);
  await host.connect('COM3');
  transport.emit('frame', { v: 1, type: 'hello', ...remoteProfile });
  await new Promise(resolve => setImmediate(resolve));
  transport.emit('frame', { v: 1, type: 'event', id: 'signal-1', event: 'signal', data: {} });
  assert.equal(socket.sent.some(item => item.event === 'signal'), false);
  assert.equal(transport.sent.at(-1).type, 'ack');
  assert.equal(transport.sent.at(-1).data.ok, false);
  await host.close();
});
