const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const MAX_FILE_BYTES = 10 * 1024 * 1024;
const MAX_STORAGE_BYTES = 1024 * 1024 * 1024;
const MAX_HISTORY = 1000;
const SAFE_MIME = /^[\w.+-]+\/[\w.+-]+$/;

class RoomStore {
  constructor(root) {
    this.root = root;
    this.filesDir = path.join(root, 'files');
    this.historyPath = path.join(root, 'history.json');
    this.history = [];
    fs.mkdirSync(this.filesDir, { recursive: true });
    this.storedBytes = fs.readdirSync(this.filesDir).reduce((total, name) => {
      try { return total + fs.statSync(path.join(this.filesDir, name)).size; } catch (_) { return total; }
    }, 0);
    try {
      const parsed = JSON.parse(fs.readFileSync(this.historyPath, 'utf8'));
      if (Array.isArray(parsed)) this.history = parsed.filter(m => m && typeof m === 'object').slice(-MAX_HISTORY);
    } catch (_) { this.history = []; }
  }
  persist() {
    const temp = `${this.historyPath}.${process.pid}.tmp`;
    fs.writeFileSync(temp, JSON.stringify(this.history), { mode: 0o600 });
    fs.renameSync(temp, this.historyPath);
  }
  addMessage(message) {
    this.history.push(message);
    if (this.history.length > MAX_HISTORY) this.history.splice(0, this.history.length - MAX_HISTORY);
    this.persist();
    return message;
  }
  addFile({ name, mime, data, user, senderId, time = Date.now() }) {
    if (typeof data !== 'string' || data.length > Math.ceil(MAX_FILE_BYTES * 4 / 3) + 256) throw new Error('الحد الأقصى للملف 10 ميغابايت.');
    const match = data.match(/^data:([^;,]+)?;base64,([A-Za-z0-9+/]*={0,2})$/);
    if (!match) throw new Error('صيغة الملف غير مدعومة.');
    const bytes = Buffer.from(match[2], 'base64');
    if (!bytes.length || bytes.length > MAX_FILE_BYTES) throw new Error('الحد الأقصى للملف 10 ميغابايت.');
    if (this.storedBytes + bytes.length > MAX_STORAGE_BYTES) throw new Error('امتلأت مساحة مشاركة الملفات (1 غيغابايت) على جهاز المضيف.');
    const safeName = path.basename(String(name || 'ملف').replace(/[\\\r\n\0]/g, '_')).slice(0, 180) || 'ملف';
    const contentType = SAFE_MIME.test(String(mime || match[1] || '')) ? String(mime || match[1]) : 'application/octet-stream';
    const id = crypto.randomUUID();
    const filePath = path.join(this.filesDir, id);
    fs.writeFileSync(filePath, bytes, { flag: 'wx', mode: 0o600 });
    const record = { id, name: safeName, mime: contentType, size: bytes.length, user, senderId, time, diskName: id };
    try { this.addMessage({ ...record, type: 'file' }); }
    catch (error) { try { fs.unlinkSync(filePath); } catch (_) {} throw error; }
    this.storedBytes += bytes.length;
    return record;
  }
  filePath(id) {
    if (typeof id !== 'string' || !/^[0-9a-f-]{36}$/i.test(id)) return null;
    const exists = this.history.some(item => item.type === 'file' && item.id === id);
    if (!exists) return null;
    const target = path.join(this.filesDir, id);
    return fs.existsSync(target) ? target : null;
  }
  publicHistory() {
    return this.history.map(({ diskName, ...message }) => message);
  }
}

module.exports = { RoomStore, MAX_FILE_BYTES, MAX_HISTORY };
