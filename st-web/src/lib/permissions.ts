/**
 * 权限点常量与旧值映射（单源）
 *
 * 9 个权限点与后端权限模型保持一致（FolderPermissionService / TeamService 同一套 key）；
 * FolderPermissionDialog / RoleManageDialog / ShareDialog 三组件统一从这里引用，
 * 避免权限点新增或调整时多文件重复维护导致漂移。
 */

/** 10 个权限点定义（key 与后端一致，label 为界面展示名；edit=OnlyOffice 编辑文档，2026-08-15 新增） */
export const PERMISSION_KEYS = [
  { key: 'view', label: '查看文件' },
  { key: 'upload', label: '上传文件' },
  { key: 'download', label: '下载文件' },
  { key: 'delete', label: '删除文件' },
  { key: 'rename', label: '重命名' },
  { key: 'move', label: '移动' },
  { key: 'share', label: '分享' },
  { key: 'edit', label: '编辑文档' },
  { key: 'manage_members', label: '管理成员' },
  { key: 'manage_settings', label: '管理设置' },
] as const;

/** 权限点集合 → 兼容旧单值 permission（与后端 legacyPermissionFromPerms 映射一致） */
export function legacyPermissionFromPerms(perms: Record<string, boolean>): number {
  if (perms.download) return 1;
  if (perms.upload) return 2;
  if (perms.delete || perms.rename || perms.move || perms.edit) return 3;
  return 0;
}

/** 旧单值权限映射为权限点集合（与 DB 迁移 34 号脚本一致） */
export function legacyToPermissions(permission: number): Record<string, boolean> {
  if (permission === 0) {
    const all: Record<string, boolean> = {};
    PERMISSION_KEYS.forEach(p => { all[p.key] = true; });
    return all;
  }
  if (permission === 1) {
    return { view: true, upload: true, download: true, delete: true, rename: true, move: true, share: true, edit: true, manage_members: false, manage_settings: false };
  }
  if (permission === 2) return { view: true };
  return { view: false };
}
