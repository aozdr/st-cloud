import type { FileNode } from '../types';

const STORAGE_KEY = 'stcloud:recentFiles';
const MAX_ITEMS = 12;

export interface RecentFile {
  id: string;
  name: string;
  suffix: string | null;
  parentId: string | null;
  path: string | null;
  fileSize: string | null;
  contentType: string | null;
  accessedAt: number;
}

export function getRecentFiles(): RecentFile[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const list = JSON.parse(raw) as RecentFile[];
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

export function addRecentFile(file: FileNode): void {
  try {
    const entry: RecentFile = {
      id: file.id,
      name: file.name,
      suffix: file.suffix,
      parentId: file.parentId,
      path: file.path,
      fileSize: file.fileSize,
      contentType: file.contentType,
      accessedAt: Date.now(),
    };
    const list = getRecentFiles().filter((f) => f.id !== file.id);
    list.unshift(entry);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list.slice(0, MAX_ITEMS)));
  } catch {
    // localStorage might be full or unavailable; ignore
  }
}

export function clearRecentFiles(): void {
  localStorage.removeItem(STORAGE_KEY);
}