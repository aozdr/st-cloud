// ==================== Auth Types ====================
export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  email?: string;
  tenantName?: string;
}

export interface LoginResponse {
  token: string;
  refreshToken: string;
  userId: string;
  username: string;
  nickname: string;
  avatar: string | null;
  roles: string[];
  permissions: string[];
  storageUsed: string;
  storageQuota: string;
}

export interface UserInfo {
  userId: string;
  username: string;
  nickname: string;
  avatar: string | null;
  roles: string[];
  permissions: string[];
  storageUsed: string;
  storageQuota: string;
}

// ==================== File Types ====================
export interface FileNode {
  id: string;
  parentId: string;
  nodeType: number; // 0=folder, 1=file
  name: string;
  path: string;
  fileSize: string | null;
  suffix: string | null;
  contentType: string | null;
  status: number; // 0=normal, 1=recycled
  thumbnailPath: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PageResult<T> {
  records: T[];
  total: string;
  size: string;
  current: string;
  pages: string;
}

export interface FileTreeNode {
  id: string;
  name: string;
  path: string;
  children: FileTreeNode[];
}

export interface StorageInfo {
  used: string;
  quota: string;
  percentage: number;
}

// ==================== Upload Types ====================
export interface UploadCheckRequest {
  fileMd5: string;
  fileSize: number;
  fileName: string;
  parentId: string;
}

export interface UploadCheckResponse {
  instant: boolean;
  fileId: string | null;
}

export interface UploadInitRequest {
  fileName: string;
  fileSize: number;
  fileMd5: string;
  totalChunks: number;
  chunkSize: number;
  parentId: string;
}

export interface UploadInitResponse {
  uploadId: string;
  s3UploadId: string;
  fileId: string;
  presignedUrls: string[];
}

export interface UploadStatusResponse {
  uploadId: string;
  uploadedChunkIndexes: number[];
}

export interface UploadMergeRequest {
  uploadId: string;
  s3UploadId: string;
}

// ==================== Recycle Bin Types ====================
export interface RecycleItem {
  id: string;
  name: string;
  nodeType: number;
  path: string;
  fileSize: string | null;
  updatedAt: string;
  remainingDays: number;
}

// ==================== Search Types ====================
export interface SearchResultVO {
  fileId: string;
  fileName: string;
  path: string;
  fileSize: string | null;
  nodeType: number | null;
  suffix: string | null;
  contentType: string | null;
  highlight: string | null;
  createdAt: string;
  updatedAt: string;
}

// ==================== Request Types ====================
export interface CreateFolderRequest {
  parentId: string;
  folderName: string;
}

export interface RenameRequest {
  newName: string;
}

export interface MoveRequest {
  nodeIds: string[];
  targetParentId: string;
}

export interface BatchIdsRequest {
  nodeIds: string[];
}

// ==================== Upload Task (Frontend) ====================
export type UploadTaskStatus = 'pending' | 'hashing' | 'uploading' | 'merging' | 'completed' | 'failed' | 'instant' | 'paused';

export interface UploadTask {
  id: string;
  file: File | null;
  parentId: string;
  replaceFileId?: string;
  status: UploadTaskStatus;
  progress: number; // 0-100
  fileSize: number;
  fileName: string;
  fileMd5?: string;
  uploadId?: string;
  s3UploadId?: string;
  presignedUrls?: string[];
  totalChunks?: number;
  uploadedChunks?: number[];
  error?: string;
  electronTaskId?: string; // Electron 模式下关联的传输任务ID
}

// ==================== Share Types ====================
export interface FileShare {
  id: string;
  shareCode: string;
  fileNodeId: string;
  fileName: string;
  shareType: number; // 0-公开 1-私密
  password?: string | null; // 提取码(仅私密分享)
  expireAt: string | null;
  permission: number; // 0-查看 1-下载 2-上传 3-编辑
  downloadLimit: number | null;
  downloadCount: number;
  viewCount: number;
  status: number; // 0-已取消 1-有效
  createdAt: string;
}

export interface CreateShareRequest {
  fileNodeId: string;
  shareType?: number;
  password?: string;
  expireAt?: string | null;
  permission?: number;
  downloadLimit?: number | null;
}

export interface ShareAccessVO {
  fileName: string;
  fileType: number;
  suffix: string | null;
  size: string | null;
  permission: number;
  isExpired: boolean;
  fileNodeId: string;
  shareType: number;
}

export interface ShareFileItem {
  id: string;
  parentId: string;
  nodeType: number;
  name: string;
  path: string;
  fileSize: string | null;
  suffix: string | null;
  contentType: string | null;
  status: number;
  thumbnailPath: string | null;
  createdAt: string;
  updatedAt: string;
}

// ==================== Team Types ====================
export interface TeamSpace {
  id: string;
  spaceName: string;
  description: string | null;
  icon: string | null;
  ownerId: string;
  ownerName: string;
  storageUsed: string;
  storageQuota: string;
  memberCount: number;
  status: number;
  createdAt: string;
  isPinned?: number;
}
  // isPinned comes from team_member, added by backend listSpaces (TODO: add to VO)

export interface TeamMember {
  id: string;
  spaceId: string;
  userId: string;
  username: string;
  nickname: string;
  avatar: string | null;
  role: number; // 0-管理员 1-编辑者 2-查看者
  joinedAt: string;
  lastActiveAt: string | null;
}

export interface CreateSpaceRequest {
  spaceName: string;
  description?: string;
  icon?: string;
  storageQuota?: number;
}

// ==================== Admin Types ====================
export interface AdminUser {
  id: string;
  username: string;
  nickname: string;
  email: string | null;
  phone: string | null;
  avatar: string | null;
  status: number;
  roles: RoleVO[];
  storageUsed: string;
  storageQuota: string;
  lastLoginAt: string | null;
  createdAt: string;
}

export interface StatsVO {
  totalUsers: number;
  activeUsers: number;
  totalFiles: number;
  totalStorageUsed: number;
  totalShares: number;
  totalTeams: number;
  cloudTotalCapacity: number | null;
  cloudStorageUsed: number;
}

export interface AuditLog {
  id: string;
  userId: string | null;
  username: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  targetName: string | null;
  detail: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  status: number;
  createdAt: string;
}
  // isPinned comes from team_member, added by backend listSpaces (TODO: add to VO)

/** 结构化审计日志详情（JSON解析后） */
export interface AuditLogDetail {
  summary?: string;
  error?: string;
  files?: AuditLogFileDetail[];
  targetFolder?: string;
  targetPath?: string;
  oldName?: string;
  newName?: string;
  fileName?: string;
  fileSize?: number;
  path?: string;
  contentType?: string;
  folderName?: string;
  parentFolder?: string;
  parentPath?: string;
  uploadId?: string;
  localPath?: string;
}

export interface AuditLogFileDetail {
  name: string;
  path: string;
  size: number;
  type: string;
  suffix?: string;
}

/** 审计日志多条件查询请求 */
export interface AuditLogQuery {
  username?: string;
  action?: string;
  targetType?: string;
  targetName?: string;
  status?: number;
  keyword?: string;
  ipAddress?: string;
  startTime?: string;
  endTime?: string;
  page: number;
  size: number;
}

// ==================== Preview Types ====================
export interface PreviewResult {
  type: string; // image/video/audio/pdf/office/text/unsupported
  url: string | null;
  content: string | null;
  suffix: string | null;
  status: string; // ready/transcoding
  size: number | null;
}

// ==================== Electron Transfer Types ====================
export type TransferType = 'upload' | 'download';
export type TransferStatus =
  | 'pending'
  | 'hashing'
  | 'uploading'
  | 'downloading'
  | 'paused'
  | 'merging'
  | 'completed'
  | 'failed';

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
  filePath?: string;
  parentId?: string;
  uploadId?: string;
  s3UploadId?: string;
  fileId?: string;
  totalChunks?: number;
  uploadedChunks?: number[];
  nodeId?: string;
  savePath?: string;
}

export interface TransferSettings {
  maxParallelTasks: number;
  uploadSpeedLimit: number; // KB/s, 0 = unlimited
  downloadSpeedLimit: number; // KB/s, 0 = unlimited
}

export interface ElectronAPI {
  isElectron: true;
  getServerUrl: () => Promise<string | null>;
  setServerUrl: (url: string) => Promise<void>;
  setAuth: (token: string, refreshToken: string) => void;
  setTransferSettings: (settings: TransferSettings) => void;
  startUpload: (filePath: string, parentId: string, replaceFileId?: string) => Promise<string>;
  pauseUpload: (taskId: string) => Promise<void>;
  resumeUpload: (taskId: string) => Promise<void>;
  cancelUpload: (taskId: string) => Promise<void>;
  startDownload: (nodeId: string, fileName: string, fileSize: number, savePath: string) => Promise<string>;
  pauseDownload: (taskId: string) => Promise<void>;
  resumeDownload: (taskId: string) => Promise<void>;
  cancelDownload: (taskId: string) => Promise<void>;
  getTasks: () => Promise<TransferTask[]>;
  onTaskUpdate: (cb: (task: TransferTask) => void) => () => void;
  selectFiles: () => Promise<string[]>;
  selectSavePath: (fileName: string) => Promise<string | null>;
  selectFolder: () => Promise<string[]>;
  getDownloadsPath: () => Promise<string>;
  openPath: (filePath: string) => Promise<string>;
  showItemInFolder: (filePath: string) => void;
  removeTask: (taskId: string) => Promise<void>;
trashItem: (filePath: string) => Promise<void>;
  syncRegister: (cloudFolderNodeId: string, localPath: string) => Promise<SyncRootVO>;
  syncListRoots: () => Promise<SyncRootVO[]>;
  syncDeleteRoot: (rootId: string) => Promise<void>;
  syncStart: (rootId: string, cloudFolderNodeId: string, localPath: string) => Promise<void>;
  syncStop: (rootId: string) => Promise<void>;
  syncStatus: () => Promise<Record<string, boolean>>;
  syncListExclusions: (rootId: string) => Promise<SyncExclusionVO[]>;
  syncAddExclusion: (rootId: string, relativePath: string) => Promise<unknown>;
  syncRemoveExclusion: (rootId: string, exclusionId: string) => Promise<void>;
  syncUpdateConflictStrategy: (rootId: string, strategy: string) => Promise<SyncRootVO>;
  syncWsStatus: () => Promise<boolean>;
  syncGetHistory: (rootId: string) => Promise<SyncHistoryEntry[]>;
  syncGetStats: (rootId: string) => Promise<SyncStats>;
  onSyncEvent: (cb: (event: { event: string; data: unknown }) => void) => () => void;
}

// ==================== Speed Limit Types ====================
export interface SpeedLimitRule {
  id: string;
  ruleName: string;
  scope: number; // 0-用户 1-角色
  targetId: string;
  targetCode: string | null;
  targetName: string | null;
  uploadSpeedLimit: number; // KB/s, 0=不限
  downloadSpeedLimit: number; // KB/s, 0=不限
  enabled: number;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PermissionVO {
  id: string;
  permissionCode: string;
  permissionName: string;
  module: string;
  description: string | null;
}

export interface RoleVO {
  id: string;
  roleCode: string;
  roleName: string;
  description: string | null;
  status: number;
  builtIn: boolean;
  dataScope: number;
  permissions: PermissionVO[];
  createdAt: string;
}

// ==================== Version Types ====================
export interface FileVersionVO {
  id: string;
  fileNodeId: string;
  versionNum: number;
  fileSize: string;
  fileMd5: string;
  modifierId: string;
  modifierName: string;
  createdAt: string;
  current: boolean;
}

// ==================== Sync Types ====================
export interface SyncRootVO {
  id: string;
  cloudFolderNodeId: string;
  cloudFolderName: string | null;
  localPathHint: string | null;
  status: number;
  conflictStrategy: string;
  cursor: number;
  lastSyncAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SyncExclusionVO {
  id: string;
  syncRootId: string;
  relativePath: string;
  createdAt: string;
}

export interface SyncHistoryEntry {
  id: number;
  rootId: string;
  action: string;
  fileName: string | null;
  relPath: string | null;
  status: string;
  detail: string | null;
  createdAt: string;
}

export interface SyncStats {
  synced: number;
  error: number;
  conflict: number;
  excluded: number;
}

declare global {
  interface Window {
    electronAPI?: ElectronAPI;
  }
}

// ==================== P0: Team Invite & Activity Types ====================
export interface TeamInvite {
  id: string;
  spaceId: string;
  inviteCode: string;
  role: number;
  createdBy: string;
  createdByName: string;
  expireAt: string | null;
  status: number; // 0-已撤销 1-有效
  createdAt: string;
}

export interface TeamActivity {
  id: string;
  userId: string;
  username: string;
  nickname: string;
  action: string;
  targetType: string | null;
  targetId: string | null;
  targetName: string | null;
  detail: string | null;
  createdAt: string;
}
export interface UserSearch {
  userId: string;
  username: string;
  nickname: string;
  avatar: string | null;
}
// ==================== P1: Notification, Comment, FolderPermission ====================
export interface NotificationItem {
  id: string; type: string; title: string; content: string | null;
  refType: string | null; refId: string | null;
  read: number; createdAt: string;
}

export interface TeamCommentItem {
  id: string; spaceId: string; nodeId: string;
  userId: string; username: string; nickname: string; avatar: string | null;
  content: string; parentId: string | null;
  mentions: string | null; createdAt: string;
  replies?: TeamCommentItem[];
}

export interface FolderPermissionItem {
  id: string; spaceId: string; folderNodeId: string;
  subjectType: string; subjectId: string; subjectName: string;
  permission: number; createdAt: string;
}
// ==================== P2: Role, Stats, Lock ====================
export interface TeamRoleInfo {
  id: string; spaceId: string; name: string;
  permissions: string; status: number; isPreset: boolean; createdAt: string;
}

export interface TeamStats {
  storageUsed: string; storageQuota: string; fileCount: string;
  fileTypeDistribution: { type: string; count: number }[];
  memberActivity: { userId: string; nickname: string; lastActiveAt: string | null }[];
  operationStats: { action: string; count: number }[];
}