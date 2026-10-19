'use strict';

(() => {
  const CLIENT_ID = /^[a-zA-Z0-9_-]{16,80}$/;

  class BluetoothPeerSocket {
    constructor({ address, localProfile, sendFrame, transport = 'bluetooth' }) {
      if (!address || typeof sendFrame !== 'function') throw new TypeError('Peer address and sendFrame are required');
      this.address = String(address);
      this._fursanAddress = `${transport}:${this.address}`;
      this.transport = transport;
      this.connected = true;
      this.id = this._fursanAddress;
      this.localProfile = { ...localProfile };
      this.remoteProfile = null;
      this.sendFrame = sendFrame;
      this.handlers = new Map();
      this.pendingAcks = new Map();
      this.sequence = 0;
    }

    on(event, callback) {
      if (typeof callback !== 'function') return this;
      const handlers = this.handlers.get(event) || new Set();
      handlers.add(callback);
      this.handlers.set(event, handlers);
      return this;
    }

    off(event, callback) {
      this.handlers.get(event)?.delete(callback);
      if (!this.handlers.get(event)?.size) this.handlers.delete(event);
      return this;
    }

    emit(event, data, callback) {
      if (!this.connected) {
        if (typeof callback === 'function') callback({ ok: false, error: 'انقطع اتصال النظير.' });
        return this;
      }
      const id = typeof callback === 'function' ? `${Date.now().toString(36)}-${++this.sequence}` : undefined;
      if (id) this.pendingAcks.set(id, callback);
      const frame = { v: 1, type: 'event', event: String(event), data };
      if (id) frame.id = id;
      Promise.resolve(this.sendFrame(frame)).then(result => {
        if (result === false) throw new Error('تعذر وضع الإطار في طابور Bluetooth.');
      }).catch(error => {
        if (!id) return;
        const pending = this.pendingAcks.get(id);
        this.pendingAcks.delete(id);
        pending?.({ ok: false, error: error.message || 'تعذر إرسال إطار Bluetooth.' });
      });
      return this;
    }

    sendHello() {
      return this.sendFrame({ v: 1, type: 'hello', clientId: this.localProfile.clientId, name: this.localProfile.name, platform: this.localProfile.platform });
    }

    receive(frame) {
      if (!this.connected || !frame || frame.v !== 1 || typeof frame.type !== 'string') return false;
      if (frame.type === 'hello') return this._receiveHello(frame);
      if (frame.type === 'ack') {
        const callback = this.pendingAcks.get(String(frame.id || ''));
        if (!callback) return false;
        this.pendingAcks.delete(String(frame.id));
        callback(frame.data || { ok: true });
        return true;
      }
      if (frame.type !== 'event' || typeof frame.event !== 'string') return false;
      const acknowledge = frame.id ? response => this.sendFrame({ v: 1, type: 'ack', id: String(frame.id), data: response || { ok: true } }) : undefined;
      let event = frame.event;
      let data = frame.data;
      if (event === 'chat' && this.remoteProfile && data && typeof data === 'object') {
        event = 'chat-message';
        data = {
          ...data,
          type: 'text',
          user: this.remoteProfile.name,
          senderId: this.remoteProfile.clientId,
          senderClientId: this.remoteProfile.clientId,
        transport: this.transport,
          time: Number(data.time) || Date.now(),
          deliveredTo: Array.isArray(data.deliveredTo) ? data.deliveredTo : [],
          readBy: Array.isArray(data.readBy) ? data.readBy : []
        };
      }
      this._dispatch(event, data, acknowledge);
      return true;
    }

    _receiveHello(frame) {
      if (!CLIENT_ID.test(String(frame.clientId || '')) || frame.clientId === this.localProfile.clientId) return false;
      this.remoteProfile = {
        clientId: String(frame.clientId),
        name: String(frame.name || 'نظير Bluetooth').replace(/[\r\n\0]/g, ' ').trim().slice(0, 40) || 'نظير Bluetooth',
        platform: frame.platform === 'android' ? 'android' : 'windows'
      };
      this._dispatch('peer-profile', this.remoteProfile);
    this._dispatch('connect', { id: this.id, transport: this.transport });
      return true;
    }

    _dispatch(event, data, acknowledge) {
      for (const callback of this.handlers.get(event) || []) {
        try { callback(data, acknowledge); } catch (error) { console.error('Bluetooth event handler failed', error); }
      }
    }

    close(reason = 'disconnect') {
      if (!this.connected) return;
      this.connected = false;
      for (const callback of this.pendingAcks.values()) callback({ ok: false, error: 'انقطع اتصال النظير.' });
      this.pendingAcks.clear();
      this._dispatch(reason, { address: this.address });
      this.handlers.clear();
    }
  }

  window.FursanBluetoothClient = BluetoothPeerSocket;
})();
