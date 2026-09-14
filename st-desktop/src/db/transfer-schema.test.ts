import assert from 'node:assert/strict';
import test from 'node:test';
import initSqlJs from 'sql.js';
import { ensureTransferTaskColumns } from './transfer-schema';

test('旧 transfer_tasks schema 可补齐 relay 元数据列', async () => {
  const SQL = await initSqlJs();
  const db = new SQL.Database();
  db.run('CREATE TABLE transfer_tasks (id TEXT PRIMARY KEY, status TEXT)');
  ensureTransferTaskColumns(db);
  const columns = db.exec('PRAGMA table_info(transfer_tasks)')[0].values.map((row) => String(row[1]));
  assert.deepEqual(columns, ['id', 'status', 'transfer_mode', 'relay_chunk_size', 'relay_limit_kb']);
  ensureTransferTaskColumns(db);
  assert.equal(db.exec('PRAGMA table_info(transfer_tasks)')[0].values.length, 5);
});
