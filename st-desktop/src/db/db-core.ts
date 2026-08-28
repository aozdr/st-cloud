import type { Database } from 'sql.js';
import fs from 'fs';

/**
 * 数据库核心：sql.js 内存实例与持久化。
 * database.ts 负责初始化（initDatabase），各域模块（transfer-tasks / sync-meta）经 getDb() 访问连接。
 */

let db: Database;
let dbPath: string;

export function setDb(instance: Database, path: string): void {
  db = instance;
  dbPath = path;
}

export function getDb(): Database {
  return db;
}

export function getDbPath(): string {
  return dbPath;
}

/** 将内存数据库写入磁盘文件 */
export function persist(): void {
  const data = db.export();
  fs.writeFileSync(dbPath, Buffer.from(data));
}
