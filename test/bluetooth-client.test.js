'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function loadClient() {
  const context = { window: {}, console, Date, Promise, Map, Set, String, Number, TypeError, Error };
  vm.runInNewContext(fs.readFileSync(require.resolve('../src/bluetooth-client'), 'utf8'), context);
  return context.window.FursanBluetoothClient;
}

const local = { clientId: 'windows-client-123456', name: 'حاسوب', platform: 'windows' };
const remote = { clientId: 'android-client-123456', name: 'هاتف', platform: 'android' };

test('sends a versioned hello and accepts a valid remote identity once', () => {
  const BluetoothPeerSocket = loadClient();
  const sent = [];
  const peer = new BluetoothPeerSocket({ address: 'AA:BB:CC:DD:EE:FF', localProfile: local, sendFrame: frame => { sent.push(frame); return true; } });
  const profiles = [];
  peer.on('peer-profile', profile => profiles.push(profile));
  assert.equal(peer.sendHello(), true);
  assert.equal(JSON.stringify(sent[0]), JSON.stringify({ v: 1, type: 'hello', ...local }));
  assert.equal(peer.receive({ v: 1, type: 'hello', ...remote }), true);
  assert.equal(JSON.stringify(profiles[0]), JSON.stringify(remote));
  assert.equal(peer.remoteProfile.clientId, remote.clientId);
  assert.equal(peer.receive({ v: 1, type: 'hello', ...local }), false);
});

test('maps direct Bluetooth chat to a normalized local chat message', () => {
  const BluetoothPeerSocket = loadClient();
  const peer = new BluetoothPeerSocket({ address: 'AA:BB:CC:DD:EE:FF', localProfile: local, sendFrame: () => true });
  const messages = [];
  peer.on('chat-message', message => messages.push(message));
  peer.receive({ v: 1, type: 'hello', ...remote });
  peer.receive({ v: 1, type: 'event', event: 'chat', data: { id: 'message-1', text: 'مرحبا', toId: local.clientId } });
  assert.equal(messages.length, 1);
  assert.equal(messages[0].senderClientId, remote.clientId);
  assert.equal(messages[0].transport, 'bluetooth');
  assert.equal(messages[0].text, 'مرحبا');
});

test('resolves acknowledged emits and clears pending callbacks on disconnect', async () => {
  const BluetoothPeerSocket = loadClient();
  const sent = [];
  const peer = new BluetoothPeerSocket({ address: 'AA:BB:CC:DD:EE:FF', localProfile: local, sendFrame: frame => { sent.push(frame); return true; } });
  const responses = [];
  peer.emit('upload-chunk', { offset: 0 }, response => responses.push(response));
  const id = sent[0].id;
  peer.receive({ v: 1, type: 'ack', id, data: { ok: true, offset: 12 } });
  assert.deepEqual(responses[0], { ok: true, offset: 12 });
  peer.emit('upload-complete', {}, response => responses.push(response));
  peer.close();
  assert.equal(responses[1].ok, false);
  assert.equal(peer.pendingAcks.size, 0);
});
