'use strict';

const { EventEmitter } = require('node:events');
const { BluetoothSerialTransport } = require('./bluetooth-serial');

const CLIENT_ID = /^[a-zA-Z0-9_-]{16,80}$/;
const INBOUND_EVENTS = new Set(['chat', 'broadcast', 'upload-init', 'upload-chunk', 'upload-complete', 'upload-cancel', 'mark-read', 'create-group']);
const SERVER_EVENTS = ['chat-message', 'file-message', 'message-receipt', 'notice', 'contacts', 'groups', 'history', 'peer-profile', 'members', 'system-message'];

class BluetoothHost extends EventEmitter {
  constructor({ SerialPort, createClient, peerPort, transportFactory } = {}) {
    super();
    if (typeof createClient !== 'function') throw new TypeError('createClient is required');
    if (!Number.isInteger(peerPort) || peerPort < 1 || peerPort > 65535) throw new RangeError('peerPort is invalid');
    this.SerialPort = SerialPort;
    this.createClient = createClient;
    this.peerPort = peerPort;
    this.transportFactory = transportFactory || (() => new BluetoothSerialTransport({ SerialPort: this.SerialPort }));
    this.transport = null;
    this.virtualSocket = null;
    this.localProfile = null;
    this.remoteProfile = null;
    this.port = null;
    this.closed = false;
    this.helloSent = false;
  }

  async listPorts() {
    const transport = this.transportFactory();
    return transport.listPorts();
  }

  async connect(path) {
    if (this.transport?.port?.isOpen) throw new Error('افصل اتصال Bluetooth الحالي قبل اختيار منفذ آخر.');
    this.closed = false;
    this.helloSent = false;
    this.remoteProfile = null;
    const transport = this.transportFactory();
    this.transport = transport;
    transport.on('connected', info => {
      this.port = info.path;
      this.emit('event', { type: 'port-open', path: info.path });
      this._sendHello();
    });
    transport.on('frame', frame => this._onFrame(frame));
    transport.on('disconnected', info => this._onDisconnected(info));
    transport.on('transport-error', error => this.emit('event', { type: 'notice', message: error.message || 'تعذر نقل Bluetooth.' }));
    return transport.connect(path);
  }

  setLocalProfile(profile) {
    if (!profile || !CLIENT_ID.test(String(profile.clientId || ''))) return false;
    this.localProfile = {
      clientId: String(profile.clientId),
      name: String(profile.name || 'فرسان لان').replace(/[\r\n\0]/g, ' ').trim().slice(0, 40) || 'فرسان لان',
      platform: 'windows'
    };
    this._sendHello();
    return true;
  }

  async sendFrame(frame) {
    if (!this.transport?.port?.isOpen) throw new Error('لا يوجد اتصال Bluetooth نشط.');
    await this.transport.sendFrame(frame);
    return { ok: true };
  }

  _sendHello() {
    if (this.helloSent || !this.localProfile || !this.transport?.port?.isOpen) return;
    this.helloSent = true;
    this.sendFrame({ v: 1, type: 'hello', ...this.localProfile }).catch(error => {
      this.helloSent = false;
      this.emit('event', { type: 'notice', message: error.message || 'تعذر إرسال هوية Bluetooth.' });
    });
  }

  _onFrame(frame) {
    if (!frame || frame.v !== 1 || typeof frame.type !== 'string') return this._protocolError('إصدار بروتوكول Bluetooth غير مدعوم.');
    if (frame.type === 'hello') return this._acceptHello(frame);
    if (frame.type === 'ack') {
      this.emit('event', { type: 'frame', frame });
      return;
    }
    if (frame.type !== 'event' || typeof frame.event !== 'string') return this._protocolError('إطار Bluetooth غير معروف.');
    if (!INBOUND_EVENTS.has(frame.event)) {
      if (frame.id) this._sendAck(frame.id, { ok: false, error: 'هذا الحدث غير مسموح عبر Bluetooth.' });
      return;
    }
    const peer = this.virtualSocket;
    if (!peer?.connected) {
      if (frame.id) this._sendAck(frame.id, { ok: false, error: 'لم تكتمل مصادقة النظير المحلي.' });
      return;
    }
    const acknowledge = frame.id ? response => this._sendAck(frame.id, response || { ok: true }) : undefined;
    try { peer.emit(frame.event, frame.data, acknowledge); }
    catch (error) {
      if (frame.id) this._sendAck(frame.id, { ok: false, error: error.message || 'تعذر تسليم الحدث.' });
    }
  }

  _acceptHello(frame) {
    const clientId = String(frame.clientId || '');
    if (!CLIENT_ID.test(clientId) || clientId === this.localProfile?.clientId) return this._protocolError('هوية Bluetooth غير صالحة.');
    if (this.remoteProfile?.clientId === clientId && this.virtualSocket?.connected) return;
    if (this.virtualSocket) this.virtualSocket.disconnect();
    const name = String(frame.name || 'نظير Bluetooth').replace(/[\r\n\0]/g, ' ').trim().slice(0, 40) || 'نظير Bluetooth';
    this.remoteProfile = { clientId, name, platform: frame.platform === 'android' ? 'android' : 'windows' };
    const client = this.createClient(`http://127.0.0.1:${this.peerPort}`, {
      transports: ['websocket'],
      reconnection: false,
      timeout: 5000,
      forceNew: true
    });
    this.virtualSocket = client;
    for (const event of SERVER_EVENTS) {
      client.on(event, data => {
        if (this.transport?.port?.isOpen) this.sendFrame({ v: 1, type: 'event', event, data }).catch(error => this.emit('event', { type: 'notice', message: error.message }));
      });
    }
    client.on('connect', () => {
      client.emit('join', { ...this.remoteProfile, local: false }, response => {
        if (response?.ok) this.emit('event', { type: 'peer-connected', ...this.remoteProfile, path: this.port });
        else this.emit('event', { type: 'notice', message: response?.error || 'تعذر اعتماد نظير Bluetooth المحلي.' });
      });
    });
    client.on('connect_error', error => this.emit('event', { type: 'notice', message: `تعذر ربط Bluetooth بالنظير المحلي: ${error.message}` }));
    client.on('disconnect', () => this.emit('event', { type: 'peer-disconnected', ...this.remoteProfile }));
    this.emit('event', { type: 'peer-pending', ...this.remoteProfile, path: this.port });
  }

  _sendAck(id, data) {
    this.sendFrame({ v: 1, type: 'ack', id: String(id), data }).catch(error => this.emit('event', { type: 'notice', message: error.message }));
  }

  _protocolError(message) {
    this.emit('event', { type: 'notice', message });
  }

  _onDisconnected(info) {
    this.closed = true;
    this.port = null;
    if (this.virtualSocket) this.virtualSocket.disconnect();
    this.virtualSocket = null;
    this.remoteProfile = null;
    this.emit('event', { type: 'disconnected', path: info?.path });
  }

  async close() {
    this.closed = true;
    if (this.virtualSocket) this.virtualSocket.disconnect();
    this.virtualSocket = null;
    this.remoteProfile = null;
    const transport = this.transport;
    this.transport = null;
    this.port = null;
    if (transport) await transport.close();
  }
}

module.exports = { BluetoothHost, INBOUND_EVENTS, SERVER_EVENTS };
