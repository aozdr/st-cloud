import { useEffect } from 'react';

/** 品牌名：所有页面标题统一以「页面名 - 星云盘」结尾 */
const BRAND = '星云盘';

/**
 * 路径前缀 → 页面名映射。
 * 顺序匹配，`/files` 前缀同时覆盖 `/files` 与 `/files/:parentId`（含分类路由）。
 */
const PAGE_TITLES: ReadonlyArray<{ prefix: string; title: string }> = [
  { prefix: '/files', title: '全部文件' },
  { prefix: '/search', title: '搜索' },
  { prefix: '/recycle', title: '回收站' },
  { prefix: '/shares', title: '分享管理' },
  { prefix: '/team', title: '团队空间' },
  { prefix: '/transfers', title: '传输管理' },
  { prefix: '/sync', title: '文件同步' },
  { prefix: '/admin', title: '系统管理' },
];

/** 根据 pathname 解析文档标题：首页或未匹配路由回落到品牌名 */
export function resolveDocumentTitle(pathname: string): string {
  if (pathname === '/') return BRAND;
  const matched = PAGE_TITLES.find(
    ({ prefix }) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
  return matched ? `${matched.title} - ${BRAND}` : BRAND;
}

/** 路由变化时同步浏览器标签页标题 */
export function useDocumentTitle(pathname: string): void {
  useEffect(() => {
    document.title = resolveDocumentTitle(pathname);
  }, [pathname]);
}
