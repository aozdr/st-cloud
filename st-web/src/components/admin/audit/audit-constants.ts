import { formatSize } from '../../../lib/utils';
import type { AuditLogDetail } from '../../../types';

const ACTION_LABELS: Record<string, string> = {
  CREATE_FOLDER: '创建文件夹',
  CREATE_USER: '创建用户',
  TEAM_CREATE_FOLDER: '创建文件夹',
  RENAME: '重命名',
  MOVE: '移动',
  COPY: '复制',
  DELETE: '删除',
  RESTORE: '恢复',
  PERMANENT_DELETE: '永久删除',
  EMPTY_RECYCLE: '清空回收站',
  RESTORE_VERSION: '恢复版本',
  UPLOAD: '上传',
  ABORT_UPLOAD: '中止上传',
  DOWNLOAD: '下载',
  SYNC_ROOT_CREATE: '注册同步',
  SYNC_ROOT_DELETE: '注销同步',
  SYNC_ROOT_TOGGLE: '同步开关',
  SHARE_CREATE: '创建分享',
  SHARE_UPDATE: '更新分享',
  SHARE_CANCEL: '取消分享',
  SHARE_ACCESS: '访问分享',
  REINDEX: '重建索引',
  REGISTER: '注册',
  LOGIN: '登录',
  LOGOUT: '退出',
  UPDATE_USER: '更新用户',
  DELETE_USER: '删除用户',
  TEAM_CREATE: '创建团队',
  TEAM_UPDATE: '更新团队',
  TEAM_DELETE: '删除团队',
  TEAM_INVITE: '邀请成员',
  TEAM_UPDATE_MEMBER: '更新成员',
  TEAM_REMOVE_MEMBER: '移除成员',
};

const ACTION_CATEGORY: Record<string, string> = {
  CREATE_FOLDER: 'file', TEAM_CREATE_FOLDER: 'file', RENAME: 'file',
  MOVE: 'file', COPY: 'file', UPLOAD: 'file', DOWNLOAD: 'file',
  RESTORE: 'file', RESTORE_VERSION: 'file', ABORT_UPLOAD: 'file',
  DELETE: 'delete', PERMANENT_DELETE: 'delete', EMPTY_RECYCLE: 'delete',
  SHARE_CREATE: 'share', SHARE_UPDATE: 'share', SHARE_CANCEL: 'share', SHARE_ACCESS: 'share',
  TEAM_CREATE: 'team', TEAM_UPDATE: 'team', TEAM_DELETE: 'team',
  TEAM_INVITE: 'team', TEAM_UPDATE_MEMBER: 'team', TEAM_REMOVE_MEMBER: 'team',
  REGISTER: 'auth', LOGIN: 'auth', LOGOUT: 'auth',
  UPDATE_USER: 'auth', DELETE_USER: 'auth',
  SYNC_ROOT_CREATE: 'sync', SYNC_ROOT_DELETE: 'sync', SYNC_ROOT_TOGGLE: 'sync',
  REINDEX: 'system',
};

const BADGE_VARIANT: Record<string, 'blue' | 'red' | 'amber' | 'purple' | 'green' | 'cyan' | 'gray'> = {
  file:   'blue',
  delete: 'red',
  share:  'amber',
  team:   'purple',
  auth:   'green',
  sync:   'cyan',
  system: 'gray',
};

const ACTION_FILTER_GROUPS = [
  { label: '文件操作', actions: ['CREATE_FOLDER', 'UPLOAD', 'DOWNLOAD', 'RENAME', 'MOVE', 'COPY', 'DELETE', 'RESTORE', 'PERMANENT_DELETE', 'EMPTY_RECYCLE', 'RESTORE_VERSION'] },
  { label: '分享', actions: ['SHARE_CREATE', 'SHARE_UPDATE', 'SHARE_CANCEL', 'SHARE_ACCESS'] },
  { label: '团队', actions: ['TEAM_CREATE', 'TEAM_UPDATE', 'TEAM_DELETE', 'TEAM_INVITE', 'TEAM_UPDATE_MEMBER', 'TEAM_REMOVE_MEMBER'] },
  { label: '账号与安全', actions: ['REGISTER', 'LOGIN', 'LOGOUT', 'UPDATE_USER', 'DELETE_USER'] },
  { label: '同步', actions: ['SYNC_ROOT_CREATE', 'SYNC_ROOT_DELETE', 'SYNC_ROOT_TOGGLE'] },
  { label: '系统', actions: ['REINDEX'] },
];

function parseAuditDetail(detail: string | null): AuditLogDetail | null {
  if (!detail) return null;
  try {
    return JSON.parse(detail) as AuditLogDetail;
  } catch {
    // 旧格式纯文本，包装为summary
    return { summary: detail };
  }
}

function formatFileSize(bytes: number | undefined): string {
  if (!bytes || bytes <= 0) return '-';
  return formatSize(bytes);
}

export { ACTION_LABELS, ACTION_CATEGORY, BADGE_VARIANT, ACTION_FILTER_GROUPS, parseAuditDetail, formatFileSize };
