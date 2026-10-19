'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { encodeFrame, FrameDecoder } = require('../src/bluetooth-protocol');

test('decodes a frame split across arbitrary RFCOMM reads', () => {
  const encoded = encodeFrame({ v: 1, type: 'event', event: 'chat', data: { text: 'مرحبا' } });
  const decoder = new FrameDecoder();
  assert.deepEqual(decoder.push(encoded.subarray(0, 2)), []);
  assert.deepEqual(decoder.push(encoded.subarray(2, 9)), []);
  assert.deepEqual(decoder.push(encoded.subarray(9)), [{ v: 1, type: 'event', event: 'chat', data: { text: 'مرحبا' } }]);
  assert.equal(decoder.pending.length, 0);
});

test('decodes multiple frames from one read and preserves order', () => {
  const frames = [{ v: 1, seq: 1 }, { v: 1, seq: 2 }, { v: 1, seq: 3 }];
  const decoder = new FrameDecoder();
  assert.deepEqual(decoder.push(Buffer.concat(frames.map(frame => encodeFrame(frame)))), frames);
});

test('rejects outbound frames over the protocol maximum', () => {
  assert.throws(() => encodeFrame({ data: 'x'.repeat(100) }, 32), RangeError);
});

test('rejects zero and oversized frame headers and clears buffered data', () => {
  const decoder = new FrameDecoder({ maxFrameBytes: 32 });
  for (const length of [0, 33]) {
    const header = Buffer.alloc(4);
    header.writeUInt32BE(length);
    assert.throws(() => decoder.push(header), RangeError);
    assert.equal(decoder.pending.length, 0);
  }
});

test('rejects malformed JSON and non-object JSON payloads', () => {
  for (const payload of ['{', '[]', 'null']) {
    const bytes = Buffer.from(payload);
    const header = Buffer.alloc(4);
    header.writeUInt32BE(bytes.length);
    const decoder = new FrameDecoder();
    assert.throws(() => decoder.push(Buffer.concat([header, bytes])));
    assert.equal(decoder.pending.length, 0);
  }
});

test('bounds the retained tail and rejects an oversized serial read chunk', () => {
  const decoder = new FrameDecoder({ maxFrameBytes: 32, maxReadChunkBytes: 64 });
  assert.deepEqual(decoder.push(Buffer.from([0, 0, 0, 32, 1])), []);
  assert.equal(decoder.pending.length, 5);
  assert.throws(() => decoder.push(Buffer.alloc(65)), /memory bound/);
  assert.ok(decoder.pending.length <= 36);
  decoder.close();
  assert.equal(decoder.pending.length, 0);
  assert.throws(() => decoder.push(Buffer.alloc(1)), /closed/);
});
