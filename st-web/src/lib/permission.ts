import { useAuthStore } from '../store/auth';

const EMPTY_PERMISSIONS: string[] = [];

/**
 * 权限校验 hook：基于当前登录用户的权限码进行编程式校验。
 * 替代散落的 roles.includes('admin') 魔法值判断。
 */
export function usePermission() {
  const permissions = useAuthStore((s) => s.user?.permissions ?? EMPTY_PERMISSIONS);
  const has = (code: string) => permissions.includes(code);
  const hasAny = (codes: string[]) => codes.some((c) => permissions.includes(c));
  const hasAll = (codes: string[]) => codes.every((c) => permissions.includes(c));
  return { has, hasAny, hasAll, permissions };
}