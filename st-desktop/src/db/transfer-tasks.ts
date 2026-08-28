import type { TransferTask, TransferType, TransferStatus } from '../types';
import { getDb, persist } from './db-core';

/** 实时速度内存缓存（不持久化，速度是瞬时值） */
const speedCache = new Map<string, number>();


interface TaskRow {
  id: string;
  type: string;
  status: string;
  file_name: string;
  file_size: number;
  transferred_bytes: number;
  progress: number;
  error: string | null;
  file_path: string | null;
  parent_id: string | null;
  upload_id: string | null;
  s3_upload_id: string | null;
  file_id: string | null;
  total_chunks: number | null;
  uploaded_chunks: string | null;
  node_id: string | null;
  save_path: string | null;
  created_at: string;
  updated_at: string;
}

function rowToTask(row: Record<string, unknown>): TransferTask {
  return {
    id: row.id as string,
    type: row.type as TransferType,
    status: row.status as TransferStatus,
    fileName: row.file_name as string,
    fileSize: row.file_size as number,
    transferredBytes: row.transferred_bytes as number,
    progress: row.progress as number,
    speed: speedCache.get(row.id as string) ?? 0,
    error: (row.error as string | null) ?? null,
    createdAt: row.created_at as string,
    filePath: (row.file_path as string | null) ?? undefined,
    parentId: (row.parent_id as string | null) ?? undefined,
    uploadId: (row.upload_id as string | null) ?? undefined,
    s3UploadId: (row.s3_upload_id as string | null) ?? undefined,
    fileId: (row.file_id as string | null) ?? undefined,
    totalChunks: (row.total_chunks as number | null) ?? undefined,
    uploadedChunks: row.uploaded_chunks
      ? (JSON.parse(row.uploaded_chunks as string) as number[])
      : undefined,
    nodeId: (row.node_id as string | null) ?? undefined,
    savePath: (row.save_path as string | null) ?? undefined,
  };
}

export function createTask(task: TransferTask): void {
  const now = new Date().toISOString();
  getDb().run(
    `INSERT INTO transfer_tasks
      (id, type, status, file_name, file_size, transferred_bytes, progress, error,
       file_path, parent_id, upload_id, s3_upload_id, file_id, total_chunks, uploaded_chunks,
       node_id, save_path, created_at, updated_at)
    VALUES
      (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      task.id,
      task.type,
      task.status,
      task.fileName,
      task.fileSize,
      task.transferredBytes,
      task.progress,
      task.error ?? null,
      task.filePath ?? null,
      task.parentId ?? null,
      task.uploadId ?? null,
      task.s3UploadId ?? null,
      task.fileId ?? null,
      task.totalChunks ?? null,
      task.uploadedChunks ? JSON.stringify(task.uploadedChunks) : null,
      task.nodeId ?? null,
      task.savePath ?? null,
      task.createdAt || now,
      now,
    ],
  );
  persist();
}

export function updateTask(id: string, fields: Partial<TransferTask>): void {
  // speed 只缓存到内存（瞬时值，不持久化到数据库）
  if (fields.speed !== undefined) {
    speedCache.set(id, fields.speed);
  }

  const sets: string[] = [];
  const values: Array<string | number | null> = [];

  const fieldMap: Record<string, string> = {
    status: 'status',
    transferredBytes: 'transferred_bytes',
    progress: 'progress',
    error: 'error',
    uploadId: 'upload_id',
    s3UploadId: 's3_upload_id',
    fileId: 'file_id',
    totalChunks: 'total_chunks',
    uploadedChunks: 'uploaded_chunks',
  };

  for (const [key, value] of Object.entries(fields)) {
    const col = fieldMap[key];
    if (col) {
      sets.push(`${col} = ?`);
      values.push(key === 'uploadedChunks' && value ? JSON.stringify(value) : ((value as string | number | null | undefined) ?? null));
    }
  }

  if (sets.length === 0) return;
  sets.push('updated_at = ?');
  values.push(new Date().toISOString());
  values.push(id);

  getDb().run(`UPDATE transfer_tasks SET ${sets.join(', ')} WHERE id = ?`, values);
  persist();
}

export function deleteTask(id: string): void {
  speedCache.delete(id);
  getDb().run('DELETE FROM transfer_tasks WHERE id = ?', [id]);
  persist();
}

export function getTask(id: string): TransferTask | null {
  const stmt = getDb().prepare('SELECT * FROM transfer_tasks WHERE id = ?');
  stmt.bind([id]);
  let result: TransferTask | null = null;
  if (stmt.step()) {
    result = rowToTask(stmt.getAsObject());
  }
  stmt.free();
  return result;
}

export function getAllTasks(): TransferTask[] {
  const stmt = getDb().prepare('SELECT * FROM transfer_tasks ORDER BY created_at DESC');
  const results: TransferTask[] = [];
  while (stmt.step()) {
    results.push(rowToTask(stmt.getAsObject()));
  }
  stmt.free();
  return results;
}

export function getPendingTasks(): TransferTask[] {
  const stmt = getDb().prepare(
    `SELECT * FROM transfer_tasks WHERE status IN ('uploading', 'downloading', 'paused', 'pending', 'hashing') ORDER BY created_at`,
  );
  const results: TransferTask[] = [];
  while (stmt.step()) {
    results.push(rowToTask(stmt.getAsObject()));
  }
  stmt.free();
  return results;
}

