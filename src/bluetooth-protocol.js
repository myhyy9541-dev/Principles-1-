'use strict';

const HEADER_BYTES = 4;
const DEFAULT_MAX_FRAME_BYTES = 64 * 1024;
const DEFAULT_MAX_READ_CHUNK_BYTES = 256 * 1024;

function encodeFrame(frame, maxFrameBytes = DEFAULT_MAX_FRAME_BYTES) {
  const payload = Buffer.from(JSON.stringify(frame), 'utf8');
  if (payload.length === 0 || payload.length > maxFrameBytes) {
    throw new RangeError(`Bluetooth frame size ${payload.length} exceeds ${maxFrameBytes} bytes`);
  }
  const header = Buffer.allocUnsafe(HEADER_BYTES);
  header.writeUInt32BE(payload.length, 0);
  return Buffer.concat([header, payload], HEADER_BYTES + payload.length);
}

class FrameDecoder {
  constructor({ maxFrameBytes = DEFAULT_MAX_FRAME_BYTES, maxReadChunkBytes = DEFAULT_MAX_READ_CHUNK_BYTES } = {}) {
    if (!Number.isInteger(maxFrameBytes) || maxFrameBytes < 1) throw new RangeError('maxFrameBytes must be a positive integer');
    if (!Number.isInteger(maxReadChunkBytes) || maxReadChunkBytes < 1) throw new RangeError('maxReadChunkBytes must be a positive integer');
    this.maxFrameBytes = maxFrameBytes;
    this.maxReadChunkBytes = maxReadChunkBytes;
    this.pending = Buffer.alloc(0);
    this.closed = false;
  }

  push(chunk) {
    if (this.closed) throw new Error('Bluetooth frame decoder is closed');
    const incoming = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    if (incoming.length > this.maxReadChunkBytes) throw new RangeError('Bluetooth read chunk exceeds configured memory bound');
    if (!incoming.length) return [];

    let data = this.pending.length ? Buffer.concat([this.pending, incoming]) : incoming;
    const frames = [];
    let offset = 0;
    while (data.length - offset >= HEADER_BYTES) {
      const size = data.readUInt32BE(offset);
      if (size === 0 || size > this.maxFrameBytes) {
        this.reset();
        throw new RangeError(`Invalid Bluetooth frame length: ${size}`);
      }
      if (data.length - offset - HEADER_BYTES < size) break;
      const start = offset + HEADER_BYTES;
      let frame;
      try {
        frame = JSON.parse(data.toString('utf8', start, start + size));
      } catch (error) {
        this.reset();
        throw new SyntaxError(`Invalid Bluetooth JSON frame: ${error.message}`);
      }
      if (!frame || typeof frame !== 'object' || Array.isArray(frame)) {
        this.reset();
        throw new TypeError('Bluetooth frame must be a JSON object');
      }
      frames.push(frame);
      offset = start + size;
    }

    this.pending = offset === data.length ? Buffer.alloc(0) : Buffer.from(data.subarray(offset));
    if (this.pending.length > HEADER_BYTES + this.maxFrameBytes) {
      this.reset();
      throw new RangeError('Bluetooth decoder buffer exceeded its configured bound');
    }
    return frames;
  }

  reset() {
    this.pending = Buffer.alloc(0);
  }

  close() {
    this.reset();
    this.closed = true;
  }
}

module.exports = { HEADER_BYTES, DEFAULT_MAX_FRAME_BYTES, DEFAULT_MAX_READ_CHUNK_BYTES, encodeFrame, FrameDecoder };
