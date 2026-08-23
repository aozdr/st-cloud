import api, { buildStreamUrl } from './api';
import type { BlankFileType, FileNode, PageResult, FileTreeNode, SearchResultPage } from '../types';

/**
 * FileSource abstracts file operations so FileBrowser can be reused
 * for both personal files and team space files.
 */
export interface FileSource {
  listFiles(parentId: string | null, page: number, size: number): Promise<PageResult<FileNode>>;
  createFolder(parentId: string | null, name: string): Promise<void>;
  /** 新建空白文件（txt/docx/xlsx/pptx），返回新节点；Office 类型由调用方决定是否跳转编辑 */
  createBlankFile(parentId: string | null, type: BlankFileType, fileName?: string): Promise<FileNode>;
  rename(nodeId: string, newName: string): Promise<void>;
  delete(nodeIds: string[]): Promise<void>;
  move(nodeIds: string[], targetParentId: string): Promise<void>;
  copy(nodeIds: string[], targetParentId: string): Promise<void>;
  loadTree(): Promise<FileTreeNode[]>;
  getDownloadUrl(nodeId: string): Promise<string>;
  /** onProgress: 打包下载过程中的已下载字节数（服务端流式打包时可能没有总大小） */
  downloadZip(nodeIds: string[], onProgress?: (loaded: number, total?: number) => void): Promise<Blob>;
  getNodeById(nodeId: string): Promise<FileNode | null>;
  resolveByPath(path: string): Promise<FileNode | null>;
}

/** Personal file source - calls /file/* endpoints */
export const personalFileSource: FileSource = {
  listFiles: (parentId, page, size) =>
    api.get('/file/list', { params: { parentId: parentId || '0', page, size } }),
  createFolder: (parentId, name) =>
    api.post('/file/folder', { parentId: parentId || '0', folderName: name }),
  createBlankFile: (parentId, type, fileName) =>
    api.post<FileNode>('/file/new', { parentId: parentId || '0', type, fileName }),
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
    const { token } = await api.post<{ token: string }>(`/file/${nodeId}/download-token`);
    return buildStreamUrl(nodeId, { token });
  },
  downloadZip: (nodeIds, onProgress) =>
    // timeout=0：打包下载可能较大且受限速影响，关闭默认 30s 超时
    api.post('/file/download/zip', { nodeIds }, {
      responseType: 'blob',
      timeout: 0,
      onDownloadProgress: (e) => onProgress?.(e.loaded, e.total || undefined),
    }) as unknown as Promise<Blob>,
};

/** Team space file source - calls /team/{spaceId}/* endpoints */
export function teamFileSource(spaceId: string): FileSource {
  return {
    listFiles: (parentId, page, size) =>
      api.get(`/team/${spaceId}/files`, { params: { parentId: parentId || undefined, page, size } }),
    createFolder: (parentId, name) =>
      api.post(`/team/${spaceId}/folder`, null, { params: { parentId: parentId || undefined, folderName: name } }),
    createBlankFile: (parentId, type, fileName) =>
      api.post<FileNode>(`/team/${spaceId}/files/new`, { parentId: parentId || undefined, type, fileName }),
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
      const { token } = await api.post<{ token: string }>(`/file/${nodeId}/download-token`);
      return buildStreamUrl(nodeId, { token });
    },
    downloadZip: (nodeIds, onProgress) =>
      api.post('/file/download/zip', { nodeIds }, {
        responseType: 'blob',
        timeout: 0,
        onDownloadProgress: (e) => onProgress?.(e.loaded, e.total || undefined),
      }) as unknown as Promise<Blob>,
  };
}

/**
 * Favorite file source - lists favorited files via /favorite/page endpoint.
 * File operations (delete/rename/download/...) delegate to the personal source.
 * createFolder/move/copy 在收藏页无意义，保留 base 实现但 UI 不暴露。
 */
export function favoriteFileSource(): FileSource {
  const base = personalFileSource;
  return {
    ...base,
    listFiles: async (_parentId, page, size) => {
      return api.get('/favorite/page', { params: { page, size } });
    },
  };
}

import type { FileCategory } from './fileTypes';

/**
 * Category file source - lists files across all folders filtered by type,
 * via the search API (matchAll keyword + suffixes/nodeType).
 * File operations (delete/rename/download/...) delegate to the personal source.
 */
export function categoryFileSource(category: FileCategory): FileSource {
  const base = personalFileSource;
  return {
    ...base,
    listFiles: async (_parentId, page, size) => {
      const params: Record<string, unknown> = { keyword: '*', page, size };
      if (category.suffixes.length) params.suffixes = category.suffixes.join(',');
      const res = (await api.get<SearchResultPage>('/search', { params })) || { records: [], total: 0 };
      const list = res.records || [];
      const records: FileNode[] = list.map((r) => ({
        id: r.fileId,
        parentId: '',
        nodeType: r.nodeType ?? 1,
        name: r.fileName.replace(/<[^>]*>/g, ''),
        path: r.path,
        fileSize: r.fileSize ?? '0',
        suffix: r.suffix,
        contentType: r.contentType,
        status: 0,
        thumbnailPath: null,
        createdAt: r.createdAt,
        updatedAt: r.updatedAt,
      }));
      const total = Number(res.total ?? 0);
      return {
        records,
        total: String(total),
        size: String(size),
        current: String(page),
        pages: String(Math.max(1, Math.ceil(total / size))),
      };
    },
  };
}
