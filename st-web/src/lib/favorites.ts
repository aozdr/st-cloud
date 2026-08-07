import type { FileNode } from '../types';

const STORAGE_KEY = 'stcloud:favorites';

export interface FavoriteFile {
  id: string;
  name: string;
  suffix: string | null;
  parentId: string | null;
  path: string | null;
  fileSize: string | null;
  contentType: string | null;
  nodeType: number;
  addedAt: number;
}

export function getFavorites(): FavoriteFile[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const list = JSON.parse(raw) as FavoriteFile[];
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

export function isFavorite(fileId: string): boolean {
  return getFavorites().some((f) => f.id === fileId);
}

export function toggleFavorite(file: FileNode): boolean {
  try {
    const list = getFavorites();
    const idx = list.findIndex((f) => f.id === file.id);
    if (idx >= 0) {
      list.splice(idx, 1);
      localStorage.setItem(STORAGE_KEY, JSON.stringify(list));
      return false;
    }
    const entry: FavoriteFile = {
      id: file.id,
      name: file.name,
      suffix: file.suffix,
      parentId: file.parentId,
      path: file.path,
      fileSize: file.fileSize,
      contentType: file.contentType,
      nodeType: file.nodeType,
      addedAt: Date.now(),
    };
    list.unshift(entry);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list));
    return true;
  } catch {
    return false;
  }
}

export function removeFavorite(fileId: string): void {
  try {
    const list = getFavorites().filter((f) => f.id !== fileId);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list));
  } catch {
    // ignore
  }
}