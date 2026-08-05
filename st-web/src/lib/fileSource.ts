import api from './api';
import type { FileNode, PageResult, FileTreeNode } from '../types';

/**
 * FileSource abstracts file operations so FileBrowser can be reused
 * for both personal files and team space files.
 */
export interface FileSource {
  listFiles(parentId: string | null, page: number, size: number): Promise<PageResult<FileNode>>;
  createFolder(parentId: string | null, name: string): Promise<void>;
  rename(nodeId: string, newName: string): Promise<void>;
  delete(nodeIds: string[]): Promise<void>;
  move(nodeIds: string[], targetParentId: string): Promise<void>;
  copy(nodeIds: string[], targetParentId: string): Promise<void>;
  loadTree(): Promise<FileTreeNode[]>;
  getDownloadUrl(nodeId: string): Promise<string>;
  downloadZip(nodeIds: string[]): Promise<Blob>;
  getNodeById(nodeId: string): Promise<FileNode | null>;
  resolveByPath(path: string): Promise<FileNode | null>;
}

/** Personal file source - calls /file/* endpoints */
export const personalFileSource: FileSource = {
  listFiles: (parentId, page, size) =>
    api.get('/file/list', { params: { parentId: parentId || '0', page, size } }),
  createFolder: (parentId, name) =>
    api.post('/file/folder', { parentId: parentId || '0', folderName: name }),
  rename: (nodeId, newName) =>
    api.put(`/file/${nodeId}/rename`, { newName }),
  delete: (nodeIds) =>
    api.post('/file/delete', { nodeIds }),
  move: (nodeIds, targetParentId) =>
    api.post('/file/move', { nodeIds, targetParentId }),
  copy: (nodeIds, targetParentId) =>
    api.post('/file/copy', { nodeIds, targetParentId }),
  loadTree: () =>
    api.get('/file/tree'),
  getNodeById: (nodeId) =>
    api.get(`/file/${nodeId}`),
  resolveByPath: (path) =>
    api.get('/file/by-path', { params: { path: path || '/' } }),
  getDownloadUrl: async (nodeId) => {
    const { token } = await api.post(`/file/${nodeId}/download-token`);
    return `/api/file/${nodeId}/stream?token=${encodeURIComponent(token || '')}`;
  },
  downloadZip: (nodeIds) =>
    api.post('/file/download/zip', { nodeIds }, { responseType: 'blob' }) as unknown as Promise<Blob>,
};

/** Team space file source - calls /team/{spaceId}/* endpoints */
export function teamFileSource(spaceId: string): FileSource {
  return {
    listFiles: (parentId, page, size) =>
      api.get(`/team/${spaceId}/files`, { params: { parentId: parentId || undefined, page, size } }),
    createFolder: (parentId, name) =>
      api.post(`/team/${spaceId}/folder`, null, { params: { parentId: parentId || undefined, folderName: name } }),
    rename: (nodeId, newName) =>
      api.put(`/team/${spaceId}/files/${nodeId}/rename`, null, { params: { newName } }),
    delete: (nodeIds) =>
      api.post(`/team/${spaceId}/files/delete`, nodeIds),
    move: (nodeIds, targetParentId) =>
      api.post(`/team/${spaceId}/files/move`, { nodeIds, targetParentId }),
    copy: (nodeIds, targetParentId) =>
      api.post(`/team/${spaceId}/files/copy`, { nodeIds, targetParentId }),
    loadTree: () =>
      api.get(`/team/${spaceId}/tree`),
    getNodeById: (nodeId) =>
      api.get(`/team/${spaceId}/files/${nodeId}`),
    resolveByPath: (path) =>
      api.get(`/team/${spaceId}/files/by-path`, { params: { path: path || '/' } }),
    getDownloadUrl: async (nodeId) => {
      const { token } = await api.post(`/file/${nodeId}/download-token`);
      return `/api/file/${nodeId}/stream?token=${encodeURIComponent(token || '')}`;
    },
    downloadZip: (nodeIds) =>
      api.post('/file/download/zip', { nodeIds }, { responseType: 'blob' }) as unknown as Promise<Blob>,
  };
}
