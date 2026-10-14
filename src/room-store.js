const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const MAX_FILE_BYTES = 10 * 1024 * 1024;
const MAX_STORAGE_BYTES = 1024 * 1024 * 1024;
const MAX_HISTORY = 5000;
const SAFE_MIME = /^[\w.+-]+\/[\w.+-]+$/;

class RoomStore {
  constructor(root) {
    this.root = root;
    this.filesDir = path.join(root, 'files');
    this.historyPath = path.join(root, 'history.json');
    this.groupsPath = path.join(root, 'groups.json');
    this.contactsPath = path.join(root, 'contacts.json');
    this.history = [];
    this.groups = [];
    this.contacts = [];
    this.uploads = new Map();
    fs.mkdirSync(this.filesDir, { recursive: true });
    this.storedBytes = fs.readdirSync(this.filesDir).reduce((total, name) => {
      if (name.endsWith('.part')) return total;
      try { return total + fs.statSync(path.join(this.filesDir, name)).size; } catch (_) { return total; }
    }, 0);
    this.history = this.loadArray(this.historyPath).slice(-MAX_HISTORY).map(message => message.channel ? message : { ...message, channel: 'general' });
    this.groups = this.loadArray(this.groupsPath);
    this.contacts = this.loadArray(this.contactsPath);
  }
  loadArray(file) { try { const value = JSON.parse(fs.readFileSync(file, 'utf8')); return Array.isArray(value) ? value : []; } catch (_) { return []; } }
  atomicWrite(file, value) {
    const temp = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(temp, JSON.stringify(value), { mode: 0o600 });
    fs.renameSync(temp, file);
  }
  persist() { this.atomicWrite(this.historyPath, this.history); }
  addMessage(message) {
    this.history.push(message);
    if (this.history.length > MAX_HISTORY) this.history.splice(0, this.history.length - MAX_HISTORY);
    this.persist();
    return message;
  }
  rememberContact(contact) {
    const clean = { clientId: contact.clientId, name: String(contact.name || 'مستخدم').slice(0, 40), lastSeen: Date.now() };
    const index = this.contacts.findIndex(item => item.clientId === clean.clientId);
    if (index >= 0) this.contacts[index] = { ...this.contacts[index], ...clean };
    else this.contacts.push(clean);
    this.atomicWrite(this.contactsPath, this.contacts);
  }
  createGroup({ id, name, members, ownerId }) {
    const cleanName = String(name || '').trim().slice(0, 60);
    const cleanMembers = [...new Set([ownerId, ...members].filter(id => typeof id === 'string' && id.length <= 80))];
    if (!cleanName || cleanMembers.length < 2) throw new Error('أضف اسماً للمجموعة وعضواً آخر على الأقل.');
    const groupId = /^[0-9a-f-]{36}$/i.test(String(id || '')) ? id : crypto.randomUUID();
    const existing = this.groups.find(item => item.id === groupId);
    if (existing) {
      if (existing.name !== cleanName || existing.ownerId !== ownerId || JSON.stringify([...existing.members].sort()) !== JSON.stringify([...cleanMembers].sort())) throw new Error('معرف المجموعة مستخدم بتفاصيل مختلفة.');
      return existing;
    }
    const group = { id: groupId, name: cleanName, members: cleanMembers, ownerId, createdAt: Date.now() };
    this.groups.push(group);
    this.atomicWrite(this.groupsPath, this.groups);
    return group;
  }
  visibleGroups(clientId) { return this.groups.filter(group => group.members.includes(clientId)); }
  groupById(id) { return this.groups.find(group => group.id === id) || null; }
  messageRecipients(message) {
    if (Array.isArray(message.recipientIds)) return message.recipientIds;
    if (message.toId) return [message.toId];
    if (message.groupId) return this.groupById(message.groupId)?.members || [];
    return [];
  }
  historyFor(clientId) {
    return this.history.filter(message => {
      if (message.channel === 'general') return true;
      return message.senderClientId === clientId || this.messageRecipients(message).includes(clientId);
    }).map(({ diskName, recipientIds, ...message }) => message);
  }
  offlineFor(clientId) {
    return this.history.filter(message => message.channel !== 'general' && this.messageRecipients(message).includes(clientId) && message.senderClientId !== clientId && !(message.deliveredTo || []).includes(clientId));
  }
  markDelivered(messageId, clientId) {
    const item = this.history.find(message => message.id === messageId);
    if (!item || !this.messageRecipients(item).includes(clientId)) return null;
    item.deliveredTo ||= [];
    if (!item.deliveredTo.includes(clientId)) item.deliveredTo.push(clientId);
    this.persist();
    return item;
  }
  markRead(messageId, clientId) {
    const item = this.history.find(message => message.id === messageId);
    if (!item || !this.messageRecipients(item).includes(clientId)) return null;
    item.readBy ||= [];
    if (!item.readBy.includes(clientId)) item.readBy.push(clientId);
    this.persist();
    return item;
  }
  addFile({ name, mime, data, user, senderId, senderClientId, channel = 'general', toId, groupId, recipientIds, time = Date.now() }) {
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
    const record = { id, name: safeName, mime: contentType, size: bytes.length, user, senderId, senderClientId, channel, toId, groupId, recipientIds, time, diskName: id };
    try { this.addMessage({ ...record, type: 'file' }); }
    catch (error) { try { fs.unlinkSync(filePath); } catch (_) {} throw error; }
    this.storedBytes += bytes.length;
    return record;
  }
  initUpload(meta) {
    if (!/^[0-9a-f-]{36}$/i.test(String(meta.transferId || ''))) throw new Error('معرف الرفع غير صالح.');
    const size = Number(meta.size);
    if (!Number.isSafeInteger(size) || size < 1 || size > MAX_FILE_BYTES) throw new Error('حجم الملف يتجاوز 10 ميغابايت أو غير صالح.');
    const transferId = meta.transferId;
    const tempPath = path.join(this.filesDir, `${transferId}.part`);
    const existing = this.uploads.get(transferId);
    if (existing && existing.senderClientId !== meta.senderClientId) throw new Error('معرف النقل مستخدم من جهاز آخر.');
    if (!fs.existsSync(tempPath)) fs.writeFileSync(tempPath, Buffer.alloc(0), { flag: 'wx', mode: 0o600 });
    const offset = fs.statSync(tempPath).size;
    if (offset > size) { fs.truncateSync(tempPath, 0); }
    const safeParts = String(meta.name || 'ملف').replace(/\\/g, '/').split('/').filter(part => part && part !== '.' && part !== '..').map(part => part.replace(/[\r\n\0]/g, '_').slice(0, 100));
    const clean = { ...meta, size, name: safeParts.join('/').slice(0, 180) || 'ملف', offset: offset > size ? 0 : offset };
    this.uploads.set(transferId, clean);
    return clean.offset;
  }
  appendUploadChunk(transferId, offset, base64, senderClientId) {
    const meta = this.uploads.get(transferId);
    if (!meta) throw new Error('جلسة رفع الملف غير موجودة؛ أعد المحاولة.');
    if (meta.senderClientId !== senderClientId) throw new Error('لا تملك صلاحية تعديل هذا النقل.');
    if (typeof base64 !== 'string' || base64.length > 180000 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(base64)) throw new Error('مقطع الملف غير صالح.');
    const bytes = Buffer.from(base64, 'base64');
    if (!bytes.length || bytes.length > 128 * 1024 || offset !== meta.offset || meta.offset + bytes.length > meta.size) throw new Error('ترتيب مقاطع الملف غير صحيح.');
    const tempPath = path.join(this.filesDir, `${transferId}.part`);
    const fd = fs.openSync(tempPath, 'r+');
    try { fs.writeSync(fd, bytes, 0, bytes.length, meta.offset); } finally { fs.closeSync(fd); }
    meta.offset += bytes.length;
    return meta.offset;
  }
  finishUpload(transferId, senderClientId) {
    const meta = this.uploads.get(transferId);
    if (!meta || meta.offset !== meta.size) throw new Error('لم يكتمل رفع الملف بعد.');
    if (meta.senderClientId !== senderClientId) throw new Error('لا تملك صلاحية إكمال هذا النقل.');
    if (this.storedBytes + meta.size > MAX_STORAGE_BYTES) throw new Error('امتلأت مساحة مشاركة الملفات (1 غيغابايت) على جهاز المضيف.');
    const filePath = path.join(this.filesDir, transferId + '.part');
    const id = crypto.randomUUID();
    fs.renameSync(filePath, path.join(this.filesDir, id));
    const mime = SAFE_MIME.test(String(meta.mime || '')) ? String(meta.mime) : 'application/octet-stream';
    const record = { id, name: meta.name, mime, size: meta.size, user: meta.user, senderId: meta.senderId, senderClientId: meta.senderClientId, channel: meta.channel, toId: meta.toId, groupId: meta.groupId, time: Date.now(), diskName: id };
    try { this.addMessage({ ...record, type: 'file', deliveredTo: [], readBy: [] }); }
    catch (error) { fs.renameSync(path.join(this.filesDir, id), filePath); throw error; }
    this.uploads.delete(transferId); this.storedBytes += meta.size;
    return record;
  }
  cancelUpload(transferId, senderClientId) {
    const meta = this.uploads.get(transferId);
    if (meta && meta.senderClientId !== senderClientId) return false;
    this.uploads.delete(transferId);
    try { fs.unlinkSync(path.join(this.filesDir, `${transferId}.part`)); } catch (_) {}
    return true;
  }
  filePath(id) {
    if (typeof id !== 'string' || !/^[0-9a-f-]{36}$/i.test(id)) return null;
    const exists = this.history.some(item => item.type === 'file' && item.id === id);
    if (!exists) return null;
    const target = path.join(this.filesDir, id);
    return fs.existsSync(target) ? target : null;
  }
  publicHistory() { return this.history.map(({ diskName, ...message }) => message); }
}

module.exports = { RoomStore, MAX_FILE_BYTES, MAX_HISTORY };
