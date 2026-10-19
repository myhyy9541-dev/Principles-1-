'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { indexedDB } = require('fake-indexeddb');

function loadStore() {
  const context = { window: { indexedDB }, Blob, Date, Promise, Uint8Array, String, Number, Error };
  vm.runInNewContext(fs.readFileSync(require.resolve('../src/bluetooth-files.js'), 'utf8'), context);
  return context.window.FursanBluetoothFiles;
}

const store = loadStore();

test('stores a Bluetooth file in bounded chunks and reconstructs exact bytes', async () => {
  const source = Uint8Array.from({ length: 80_000 }, (_, index) => index % 251);
  const file = new Blob([source], { type: 'application/octet-stream' });
  Object.defineProperty(file, 'name', { value: 'sample.bin' });
  await store.saveBlob('file-1', file, file.name, file.type);
  const result = await store.getBlob('file-1');
  assert.equal(result.metadata.size, source.length);
  assert.equal(result.metadata.name, 'sample.bin');
  assert.deepEqual(new Uint8Array(await result.blob.arrayBuffer()), source);
});

test('rejects incomplete Bluetooth files and removes their stored chunks', async () => {
  await store.putChunk('file-2', 0, Uint8Array.from([1, 2, 3]));
  await store.commit({ id: 'file-2', name: 'partial.bin', mime: 'application/octet-stream', size: 4 });
  await assert.rejects(() => store.getBlob('file-2'), /غير مكتمل/);
  await store.remove('file-2');
  await assert.rejects(() => store.getBlob('file-2'), /غير محفوظ/);
});
