'use strict';

const { EventEmitter } = require('node:events');
const { encodeFrame, FrameDecoder } = require('./bluetooth-protocol');

class BluetoothSerialTransport extends EventEmitter {
  constructor({ SerialPort, maxFrameBytes } = {}) {
    super();
    if (typeof SerialPort !== 'function') throw new TypeError('SerialPort constructor is required');
    this.SerialPort = SerialPort;
    this.maxFrameBytes = maxFrameBytes;
    this.port = null;
    this.path = null;
    this.decoder = null;
    this.closing = false;
  }

  async listPorts() {
    const ports = await this.SerialPort.list();
    return ports
      .filter(port => /^COM\d+$/i.test(String(port.path || '')))
      .map(port => ({
        path: String(port.path).toUpperCase(),
        label: String(port.manufacturer || port.friendlyName || port.pnpId || port.path).replace(/[\r\n\0]/g, ' ').slice(0, 120)
      }))
      .sort((left, right) => left.path.localeCompare(right.path, undefined, { numeric: true }));
  }

  async connect(path) {
    const normalized = String(path || '').toUpperCase();
    if (!/^COM\d+$/.test(normalized)) throw new Error('اختر منفذ Bluetooth COM صالحاً من قائمة المنافذ.');
    if (this.port?.isOpen) throw new Error('يوجد اتصال Bluetooth نشط بالفعل؛ افصله أولاً.');

    const port = new this.SerialPort({ path: normalized, baudRate: 115200, autoOpen: false });
    this.port = port;
    this.path = normalized;
    this.decoder = new FrameDecoder({ ...(this.maxFrameBytes ? { maxFrameBytes: this.maxFrameBytes } : {}) });
    port.on('data', chunk => this._onData(chunk));
    port.on('error', error => this._fail(error));
    port.on('close', () => this._onClose());

    await new Promise((resolve, reject) => {
      port.open(error => {
        if (error) {
          this._clearPort(port);
          reject(error);
        } else {
          this.emit('connected', { path: normalized });
          resolve();
        }
      });
    });
    return { path: normalized };
  }

  sendFrame(frame) {
    const port = this.port;
    if (!port?.isOpen) return Promise.reject(new Error('لا يوجد منفذ Bluetooth مفتوح.'));
    let bytes;
    try { bytes = encodeFrame(frame, this.maxFrameBytes); }
    catch (error) { return Promise.reject(error); }

    return new Promise((resolve, reject) => {
      port.write(bytes, error => {
        if (error) return reject(error);
        port.drain(drainError => drainError ? reject(drainError) : resolve());
      });
    });
  }

  _onData(chunk) {
    try {
      for (const frame of this.decoder.push(chunk)) this.emit('frame', frame);
    } catch (error) {
      this._fail(error);
    }
  }

  _fail(error) {
    this.emit('transport-error', error);
    this.close().catch(() => {});
  }

  _onClose() {
    const path = this.path;
    this._clearPort(this.port);
    if (!this.closing) this.emit('disconnected', { path });
  }

  _clearPort(port) {
    if (this.port !== port) return;
    this.decoder?.close();
    this.decoder = null;
    this.port = null;
    this.path = null;
  }

  async close() {
    const port = this.port;
    if (!port) return;
    this.closing = true;
    try {
      if (port.isOpen) await new Promise(resolve => port.close(() => resolve()));
    } finally {
      this._clearPort(port);
      this.closing = false;
    }
  }
}

module.exports = { BluetoothSerialTransport };
