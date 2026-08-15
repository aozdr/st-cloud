// ==================== 共享类型定义 ====================

export type TransferType = 'upload' | 'download';
export type TransferStatus =
  | 'pending'
  | 'hashing'
  | 'uploading'
  | 'downloading'
  | 'paused'
  | 'merging'
  | 'completed'
  | 'failed'
  | 'cancelled';

export interface TransferTask {
  id: string;
  type: TransferType;
  status: TransferStatus;
  fileName: string;
  fileSize: number;
  transferredBytes: number;
  speed: number;
  progress: number;
  error: string | null;
  createdAt: string;
  // 上传专属
  filePath?: string;
  parentId?: string;
  replaceFileId?: string;
  uploadId?: string;
  s3UploadId?: string;
  fileId?: string;
  totalChunks?: number;
  uploadedChunks?: number[];
  transferMode?: 'direct' | 'relay';
  relayLimitKb?: number; // 中转模式实际生效限速(KB/s)，用于限速徽标
  // 下载专属
  nodeId?: string;
  savePath?: string;
}

// ==================== IPC 接口定义 ====================

export interface TransferSettings {
  maxParallelTasks: number;
  uploadSpeedLimit: number; // KB/s, 0 = unlimited
  downloadSpeedLimit: number; // KB/s, 0 = unlimited
}

export interface ElectronAPI {
  isElectron: true;
  // 服务器地址
  getServerUrl: () => Promise<string | null>;
  setServerUrl: (url: string) => Promise<void>;
  // 认证
  setAuth: (token: string, refreshToken: string) => void;
  // 传输设置
  setTransferSettings: (settings: TransferSettings) => void;
  // 上传
  startUpload: (filePath: string, parentId: string, replaceFileId?: string) => Promise<string>;
  pauseUpload: (taskId: string) => Promise<void>;
  resumeUpload: (taskId: string) => Promise<void>;
  cancelUpload: (taskId: string) => Promise<void>;
  // 下载
  startDownload: (nodeId: string, fileName: string, fileSize: number, savePath: string) => Promise<string>;
  pauseDownload: (taskId: string) => Promise<void>;
  resumeDownload: (taskId: string) => Promise<void>;
  cancelDownload: (taskId: string) => Promise<void>;
  // 查询
  getTasks: () => Promise<TransferTask[]>;
  // 事件监听
  onTaskUpdate: (cb: (task: TransferTask) => void) => () => void;
  // 原生文件选择
  selectFiles: () => Promise<string[]>;
  selectSavePath: (fileName: string) => Promise<string | null>;
  selectFolder: () => Promise<string[]>;
  getDownloadsPath: () => Promise<string>;
  openPath: (filePath: string) => Promise<string>;
  showItemInFolder: (filePath: string) => void;
  // 删除任务记录
  removeTask: (taskId: string) => Promise<void>;
  // 移到回收站
  trashItem: (filePath: string) => Promise<void>;

  // 文件同步
  syncRegister: (cloudFolderNodeId: string, localPath: string) => Promise<unknown>;
  syncListRoots: () => Promise<unknown[]>;
  syncDeleteRoot: (rootId: string) => Promise<void>;
  syncStart: (rootId: string, cloudFolderNodeId: string, localPath: string) => Promise<void>;
  syncStop: (rootId: string) => Promise<void>;
  syncStatus: () => Promise<Record<string, boolean>>;
  syncListExclusions: (rootId: string) => Promise<{ id: string; syncRootId: string; relativePath: string; createdAt: string }[]>;
  syncAddExclusion: (rootId: string, relativePath: string) => Promise<unknown>;
  syncRemoveExclusion: (rootId: string, exclusionId: string) => Promise<void>;
  syncUpdateConflictStrategy: (rootId: string, strategy: string) => Promise<unknown>;
  syncWsStatus: () => Promise<boolean>;
  syncGetHistory: (rootId: string) => Promise<{ id: number; rootId: string; action: string; fileName: string | null; relPath: string | null; status: string; detail: string | null; createdAt: string }[]>;
  syncGetStats: (rootId: string) => Promise<{ synced: number; error: number; conflict: number; excluded: number }>;
  onSyncEvent: (cb: (event: { event: string; data: unknown }) => void) => () => void;
}

// ==================== 后端 API 响应类型 ====================

export interface UploadCheckResponse {
  instant: boolean;
  fileId?: string;
}

export interface UploadInitResponse {
  uploadId: string;
  s3UploadId: string;
  fileId: string;
  presignedUrls: string[];
  transferMode?: 'direct' | 'relay';
  relayChunkSize?: number;
  relayRateKb?: number;
}

export interface UploadStatusResponse {
  uploadId: string;
  uploadedChunkIndexes: number[];
  presignedUrls: { [key: number]: string };
}

export interface UploadMergeRequest {
  uploadId: string;
  s3UploadId: string;
  fileId: string;
}

declare global {
  interface Window {
    electronAPI?: ElectronAPI;
  }
}
