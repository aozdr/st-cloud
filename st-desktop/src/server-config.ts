/**
 * 服务器地址配置（主进程持久化）
 * 保存到 userData 目录下的 server-config.json
 */
import { app } from 'electron';
import path from 'path';
import fs from 'fs';

const DEFAULT_URL = 'http://127.0.0.1:8080';

let configPath: string;
let cachedUrl: string = DEFAULT_URL;

function ensureConfigPath(): void {
  if (!configPath) {
    configPath = path.join(app.getPath('userData'), 'server-config.json');
  }
}

export function loadServerUrl(): string {
  ensureConfigPath();
  try {
    if (fs.existsSync(configPath)) {
      const raw = fs.readFileSync(configPath, 'utf-8');
      const data = JSON.parse(raw);
      if (data && typeof data.url === 'string' && data.url) {
        cachedUrl = data.url;
      }
    }
  } catch {
    // 读取失败使用默认值
  }
  return cachedUrl;
}

export function getServerUrl(): string {
  return cachedUrl;
}

export function saveServerUrl(url: string): void {
  ensureConfigPath();
  const v = (url || '').trim();
  cachedUrl = v || DEFAULT_URL;
  try {
    fs.writeFileSync(configPath, JSON.stringify({ url: cachedUrl }, null, 2), 'utf-8');
  } catch {
    // 持久化失败不影响内存使用
  }
}
