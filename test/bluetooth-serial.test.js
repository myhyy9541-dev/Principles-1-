'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { BluetoothSerialTransport } = require('../src/bluetooth-serial');
const { encodeFrame } = require('../src/bluetooth-protocol');

function createMockSerialPortClass() {
  class MockSerialPort extends EventEmitter {
    static devices = [{ path: 'COM10', manufacturer: 'Bluetooth SPP' }, { path: 'COM2', pnpId: 'Bluetooth link' }, { path: '/dev/ttyS0' }];
    static instances = [];
    static async list() { return MockSerialPort.devices; }
    constructor(options) { super(); this.options = options; this.isOpen = false; this.writes = []; MockSerialPort.instances.push(this); }
    open(callback) { this.isOpen = true; callback(null); this.emit('open'); }
    write(bytes, callback) { this.writes.push(Buffer.from(bytes)); callback(null); }
    drain(callback) { callback(null); }
    close(callback) { this.isOpen = false; callback(null); this.emit('close'); }
    inject(bytes) { this.emit('data', bytes); }
  }
  return MockSerialPort;
}

test('lists only Windows COM ports in stable numeric order', async () => {
  const transport = new BluetoothSerialTransport({ SerialPort: createMockSerialPortClass() });
  assert.deepEqual(await transport.listPorts(), [
    { path: 'COM2', label: 'Bluetooth link' },
    { path: 'COM10', label: 'Bluetooth SPP' }
  ]);
});

test('rejects malformed port names and transmits framed JSON over an open COM port', async () => {
  const SerialPort = createMockSerialPortClass();
  const transport = new BluetoothSerialTransport({ SerialPort });
  await assert.rejects(transport.connect('/dev/ttyS0'), /COM/);
  await transport.connect('com4');
  const frame = { v: 1, type: 'hello', clientId: 'client-1234567890' };
  await transport.sendFrame(frame);
  assert.deepEqual(SerialPort.instances[0].writes, [encodeFrame(frame)]);
  await transport.close();
});

test('decodes split inbound frames and reports invalid frames without retaining a link', async () => {
  const SerialPort = createMockSerialPortClass();
  const transport = new BluetoothSerialTransport({ SerialPort });
  const port = await transport.connect('COM7').then(() => transport.port);
  const received = [];
  transport.on('frame', frame => received.push(frame));
  const bytes = encodeFrame({ v: 1, type: 'hello', name: 'نظير' });
  port.inject(bytes.subarray(0, 3));
  port.inject(bytes.subarray(3));
  assert.deepEqual(received, [{ v: 1, type: 'hello', name: 'نظير' }]);
  await transport.close();
  assert.equal(transport.port, null);
});
