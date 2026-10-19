(() => {
  'use strict';
  const DB_NAME = 'fursan-lan-bluetooth-files-v1';
  const DB_VERSION = 1;
  const CHUNK_BYTES = 32 * 1024;
  let databasePromise;

  function openDatabase() {
    if (databasePromise) return databasePromise;
    databasePromise = new Promise((resolve, reject) => {
      if (!window.indexedDB) return reject(new Error('التخزين المحلي غير متاح في هذا المتصفح.'));
      const request = window.indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains('files')) db.createObjectStore('files', { keyPath: 'id' });
        if (!db.objectStoreNames.contains('chunks')) {
          const chunks = db.createObjectStore('chunks', { keyPath: 'key' });
          chunks.createIndex('fileId', 'fileId', { unique: false });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => { databasePromise = null; reject(request.error || new Error('تعذر فتح مخزن الملفات.')); };
      request.onblocked = () => { databasePromise = null; reject(new Error('أغلق نسخة التطبيق الأخرى ثم أعد المحاولة.')); };
    });
    return databasePromise;
  }

  function transactionDone(transaction) {
    return new Promise((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error || new Error('فشلت كتابة الملف.'));
      transaction.onabort = () => reject(transaction.error || new Error('أُلغيت كتابة الملف.'));
    });
  }

  function requestResult(request) {
    return new Promise((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error('تعذر قراءة مخزن الملفات.'));
    });
  }

  async function putChunk(id, offset, bytes) {
    const db = await openDatabase();
    const tx = db.transaction('chunks', 'readwrite');
    tx.objectStore('chunks').put({ key: `${id}:${String(offset).padStart(12, '0')}`, fileId: id, offset, bytes: bytes.slice().buffer });
    await transactionDone(tx);
  }

  async function commit(metadata) {
    const db = await openDatabase();
    const tx = db.transaction('files', 'readwrite');
    tx.objectStore('files').put({ ...metadata, updatedAt: Date.now() });
    await transactionDone(tx);
  }

  async function saveBlob(id, file, name, mime) {
    if (!file || !Number.isSafeInteger(file.size) || file.size < 1 || file.size > 10 * 1024 * 1024) throw new Error('حجم الملف غير صالح أو يتجاوز 10 م.ب.');
    try {
      for (let offset = 0; offset < file.size; offset += CHUNK_BYTES) {
        const bytes = new Uint8Array(await file.slice(offset, Math.min(offset + CHUNK_BYTES, file.size)).arrayBuffer());
        await putChunk(id, offset, bytes);
      }
      await commit({ id, name: String(name || file.name || 'ملف').slice(0, 180), mime: String(mime || file.type || 'application/octet-stream'), size: file.size });
    } catch (error) {
      await remove(id).catch(() => {});
      throw error;
    }
  }

  async function getBlob(id) {
    const db = await openDatabase();
    const tx = db.transaction(['files', 'chunks'], 'readonly');
    const done = transactionDone(tx);
    const fileRequest = requestResult(tx.objectStore('files').get(id));
    const chunksRequest = requestResult(tx.objectStore('chunks').index('fileId').getAll(id));
    const [metadata, chunks] = await Promise.all([fileRequest, chunksRequest]);
    await done;
    if (!metadata) throw new Error('الملف غير محفوظ على هذا الجهاز.');
    chunks.sort((a, b) => a.offset - b.offset);
    const size = chunks.reduce((total, item) => total + item.bytes.byteLength, 0);
    if (size !== metadata.size) throw new Error('الملف المحلي غير مكتمل؛ اطلب إعادة إرساله.');
    return { metadata, blob: new Blob(chunks.map(item => item.bytes), { type: metadata.mime || 'application/octet-stream' }) };
  }

  async function remove(id) {
    const db = await openDatabase();
    const tx = db.transaction(['files', 'chunks'], 'readwrite');
    const done = transactionDone(tx);
    const store = tx.objectStore('chunks');
    const request = store.index('fileId').openCursor(id);
    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) { cursor.delete(); cursor.continue(); }
    };
    tx.objectStore('files').delete(id);
    await done;
  }

  window.FursanBluetoothFiles = { putChunk, commit, saveBlob, getBlob, remove, chunkBytes: CHUNK_BYTES };
})();
