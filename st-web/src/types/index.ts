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
  // 文件锁定字段（团队空间，以后端下发为准；null=未锁定）
  lockedBy?: number | null; // 锁定人ID
  lockedAt?: string | null; // 锁定时间
  lockExpireAt?: string | null; // 锁过期时间，null=永久锁定
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
  transferMode?: 'direct' | 'relay';
  relayChunkSize?: number;
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

/** 可新建的空白文件类型（服务端白名单：txt/docx/xlsx/pptx） */
export type BlankFileType = 'txt' | 'docx' | 'xlsx' | 'pptx';

/** 新建空白文件请求：POST /api/file/new 或 POST /api/team/{spaceId}/files/new */
export interface CreateBlankFileRequest {
  parentId: string;
  type: BlankFileType;
  /** 文件名（可选，空则后端用默认名；无后缀自动补对应类型后缀） */
  fileName?: string;
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
  transferMode?: 'direct' | 'relay';
  relayLimitKb?: number; // 中转模式实际生效限速(KB/s)，用于限速徽标与预估剩余时间
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
  allowDownload: number; // 0-禁止下载/流式 1-允许（下载URL与流式统一开关）
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
  permissions?: string; // 分享权限点 JSON 字符串（如 {"view":true,"download":true}，后端 String JSON 契约）
  allowDownload?: number; // 0-禁止下载/流式 1-允许（不传时后端与 permission 联动）
  downloadLimit?: number | null;
}

export interface ShareAccessVO {
  fileName: string;
  fileType: number;
  suffix: string | null;
  size: string | null;
  permission: number;
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
  transferMode?: 'direct' | 'relay';
  relayLimitKb?: number;
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
  subjectType: 'all' | 'member' | 'role'; // all=全体(管理员除外)
  subjectId: string; subjectName: string;
  permission: number;
  permissions?: Record<string, boolean>; // 权限点集合（优先）
  createdAt: string;
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

// ==================== OnlyOffice Editor Types ====================
/** 在线文档编辑：GET /api/file/{nodeId}/editor/config 返回体 */
export interface EditorConfigResponse {
  editorUrl: string;
  config: OnlyOfficeConfig;
}

/** OnlyOffice 文档权限（后端按权限判定下发，前端不自行放宽） */
export interface OnlyOfficePermissions {
  edit?: boolean;
  download?: boolean;
  print?: boolean;
  comment?: boolean;
  copy?: boolean;
  review?: boolean;
}

/** OnlyOffice document 节点：url 为可访问的文件流地址 */
export interface OnlyOfficeDocument {
  fileType?: string;
  key: string;
  title: string;
  url: string;
  permissions?: OnlyOfficePermissions;
}

/** OnlyOffice 编辑器用户信息 */
export interface OnlyOfficeUser {
  id: string;
  name: string;
}

/** OnlyOffice editorConfig（后端生成；goback/events 由前端本地注入，不参与签名） */
export interface OnlyOfficeEditorConfig {
  mode?: 'edit' | 'view';
  callbackUrl?: string;
  user?: OnlyOfficeUser;
  lang?: string;
  customization?: {
    goback?: { url?: string; text?: string } | boolean;
    toolbarHideFileName?: boolean;
    [key: string]: unknown;
  };
  [key: string]: unknown;
}

/** OnlyOffice DocEditor 初始化配置（含后端签发的 token） */
export interface OnlyOfficeConfig {
  documentType?: string;
  document: OnlyOfficeDocument;
  editorConfig: OnlyOfficeEditorConfig;
  type?: string;
  token?: string;
  /** 前端本地注册的事件回调（onRequestClose / onError 等），不参与后端签名 */
  events?: Record<string, (...args: unknown[]) => void>;
}
