#!/usr/bin/env node
// Node 24+. Reads a temporary SQLite snapshot, never writes to the official client's files.
// The output is a private credential file. Do not log it, put it in source control, or publish it.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { DatabaseSync } = require('node:sqlite');

function readProto(buffer) {
  let position = 0;
  function varint() {
    let value = 0n;
    for (let i = 0; i < 10; i++) {
      if (position >= buffer.length) throw Error('Invalid saved account');
      const byte = buffer[position++];
      value |= BigInt(byte & 127) << BigInt(i * 7);
      if (!(byte & 128)) return value;
    }
    throw Error('Invalid saved account');
  }
  const fields = [];
  while (position < buffer.length) {
    if (fields.length > 10000) throw Error('Invalid saved account');
    const tag = varint(), field = Number(tag >> 3n), wire = Number(tag & 7n);
    if (!field) throw Error('Invalid saved account');
    if (wire === 2) {
      const length = Number(varint());
      if (!Number.isSafeInteger(length) || length < 0 || length > buffer.length - position) throw Error('Invalid saved account');
      fields.push({ field, bytes: buffer.subarray(position, position + length) });
      position += length;
    } else if (wire === 0) fields.push({ field, integer: varint() });
    else if (wire === 1 || wire === 5) { position += wire === 1 ? 8 : 4; if (position > buffer.length) throw Error('Invalid saved account'); }
    else throw Error('Invalid saved account');
  }
  return fields;
}
const value = (fields, number) => fields.find(f => f.field === number)?.bytes;
const text = (fields, number) => value(fields, number)?.toString('utf8') || '';

function extract(db) {
  const accountRow = db.prepare("SELECT CAST(value AS BLOB) AS value FROM AccountData WHERE key='Account'").get();
  if (!accountRow) throw Error('Log in to the official desktop client first');
  const account = readProto(Buffer.from(accountRow.value));
  const session = value(account, 50);
  const candidates = [];
  for (const row of db.prepare("SELECT CAST(value AS BLOB) AS value FROM ProtobufItems WHERE key != 'Checksum'").all()) {
    const item = readProto(Buffer.from(row.value));
    const config = value(item, 21);
    const chat = config && value(readProto(config), 5);
    if (!chat) continue;
    const fields = readProto(chat);
    const domain = text(fields, 1), username = text(fields, 2), password = text(fields, 3);
    if (!/^chat(?:-[1-9])?\.(?:tmspk\.net|teamspeak\.com)$/.test(domain) || !username || !password) continue;
    candidates.push({ domain, user_id: '@' + username + ':' + domain, chat_password: password });
  }
  const unique = [...new Map(candidates.map(c => [c.user_id, c])).values()];
  if (unique.length !== 1) throw Error('Expected one central chat account; open cloud chat in the official client first');
  return { format: 'shengqiao-account', version: 1, ...unique[0],
    myts_session: session ? text(readProto(session), 1) : '', username: text(account, 4) };
}

function main() {
  const args = process.argv.slice(2);
  const arg = name => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
  const profile = path.resolve(arg('--profile') || path.join(process.env.APPDATA || '', 'TeamSpeak', 'Default'));
  const output = path.resolve(arg('--output') || 'dist/private-account/TeamSpeak-account.tsaccount');
  if (!output.endsWith('.tsaccount')) throw Error('Choose a .tsaccount output file');
  if (fs.existsSync(output)) throw Error('Output already exists; choose a new filename');
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'shengqiao-account-'));
  let db;
  try {
    for (const name of ['settings.db', 'settings.db-wal', 'settings.db-shm']) {
      const source = path.join(profile, name);
      if (fs.existsSync(source)) fs.copyFileSync(source, path.join(temp, name));
    }
    db = new DatabaseSync(path.join(temp, 'settings.db'));
    const check = db.prepare('PRAGMA quick_check').get();
    if (!Object.values(check).includes('ok')) throw Error('Snapshot was inconsistent; retry the export');
    const data = extract(db);
    const body = JSON.stringify(data, null, 2);
    if (Buffer.byteLength(body) > 32768) throw Error('Transfer file too large');
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, body, { encoding: 'utf8', flag: 'wx', mode: 0o600 });
    console.log(JSON.stringify({ exported: true, file: output, containsPrivateCredentials: true }));
  } finally {
    db?.close();
    for (const name of fs.readdirSync(temp)) fs.unlinkSync(path.join(temp, name));
    fs.rmdirSync(temp);
  }
}

if (require.main === module) {
  try { main(); } catch (error) { console.error('Account export failed: ' + error.message); process.exitCode = 1; }
}
module.exports = { extract, readProto };
