import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import initSqlJs from 'sql.js';
import { setDb } from './db/db-core';
import { createTask, getTask } from './db/transfer-tasks';
import type { TransferTask } from './types';

test('上传和下载任务可通过真实 SQLite 创建并在重载后完整读回', async () => {
  const SQL = await initSqlJs({
    locateFile: () => path.join(__dirname, '..', 'node_modules/sql.js/dist/sql-wasm.wasm'),
  });
  const directory = fs.mkdtempSync(path.join(__dirname, 'transfer-test-'));
  const databasePath = path.join(directory, 'transfers.db');
  let db = new SQL.Database();
  try {
    db.run(`CREATE TABLE transfer_tasks (
      id TEXT PRIMARY KEY, type TEXT NOT NULL, status TEXT NOT NULL,
      file_name TEXT NOT NULL, file_size INTEGER NOT NULL,
      transferred_bytes INTEGER DEFAULT 0, progress INTEGER DEFAULT 0, error TEXT,
      file_path TEXT, parent_id TEXT, space_id TEXT, upload_id TEXT, s3_upload_id TEXT,
      file_id TEXT, total_chunks INTEGER, uploaded_chunks TEXT,
      transfer_mode TEXT, relay_chunk_size INTEGER, relay_limit_kb INTEGER, node_id TEXT,
      save_path TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    )`);
    setDb(db, databasePath);
    const common = {
      status: 'pending' as const, fileName: '测试.zip', fileSize: 1024,
      transferredBytes: 0, progress: 0, speed: 0, error: null,
      createdAt: '2026-09-12T00:00:00.000Z',
    };
    const upload: TransferTask = {
      ...common, id: 'upload-test', type: 'upload', filePath: 'E:\\测试.zip',
      parentId: '9223372036854775800', spaceId: '9223372036854775801',
      uploadId: 'upload-session', s3UploadId: 'multipart-session',
      fileId: '9223372036854775802', totalChunks: 3, uploadedChunks: [0, 2],
      transferMode: 'relay', relayChunkSize: 204800, relayLimitKb: 100,
    };
    const download: TransferTask = {
      ...common, id: 'download-test', type: 'download',
      nodeId: '9223372036854775803', savePath: 'E:\\下载\\测试.zip',
    };
    // 调用生产写入路径并重载持久化文件，覆盖两种传输共用的列与参数映射。
    createTask(upload);
    createTask(download);
    db.close();
    db = new SQL.Database(fs.readFileSync(databasePath));
    setDb(db, databasePath);
    for (const expected of [upload, download]) {
      const actual = getTask(expected.id);
      assert.ok(actual);
      for (const [key, value] of Object.entries(expected)) {
        assert.deepEqual(actual[key as keyof TransferTask], value, key);
      }
    }
    assert.equal(getTask(download.id)?.spaceId, undefined);
    assert.equal(getTask(upload.id)?.nodeId, undefined);
  } finally {
    db.close();
    if (fs.existsSync(databasePath)) fs.unlinkSync(databasePath);
    fs.rmdirSync(directory);
  }
});
