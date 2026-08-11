import type { ReactNode } from 'react';

/**
 * 通用空状态组件：SVG 插图 + 标题 + 描述 + 可选操作按钮
 * 用于回收站空、收藏空、搜索无结果等场景
 */
interface EmptyStateProps {
  /** 插图类型 */
  type?: 'folder' | 'trash' | 'search' | 'star' | 'generic';
  title: string;
  description?: string;
  action?: ReactNode;
}

const ILLUSTRATIONS: Record<string, JSX.Element> = {
  folder: (
    <svg width="96" height="80" viewBox="0 0 96 80" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden>
      <ellipse cx="48" cy="72" rx="36" ry="5" fill="rgb(var(--color-primary-100))" />
      <path d="M20 16C20 14.9 20.9 14 22 14H42L50 22H74C75.1 22 76 22.9 76 24V60C76 61.1 75.1 62 74 62H22C20.9 62 20 61.1 20 60V16Z" fill="rgb(var(--color-primary-100))" stroke="rgb(var(--color-primary-300))" strokeWidth="1.5" />
      <path d="M20 28H76V58C76 59.1 75.1 60 74 60H22C20.9 60 20 59.1 20 58V28Z" fill="rgb(var(--color-primary-200))" />
      <circle cx="40" cy="42" r="4" fill="rgb(var(--color-primary-400))" opacity="0.6" />
      <path d="M32 52L40 44L48 50L56 42L64 48V54C64 55.1 63.1 56 62 56H34C32.9 56 32 55.1 32 54V52Z" fill="rgb(var(--color-primary-400))" opacity="0.5" />
    </svg>
  ),
  trash: (
    <svg width="96" height="80" viewBox="0 0 96 80" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden>
      <ellipse cx="48" cy="72" rx="32" ry="4" fill="rgb(var(--color-surface-3))" opacity="0.5" />
      <path d="M32 24H64L60 68C60 69.1 59.1 70 58 70H38C36.9 70 36 69.1 36 68L32 24Z" fill="rgb(var(--color-surface-3))" stroke="rgb(var(--color-border))" strokeWidth="1.5" />
      <path d="M28 22H68" stroke="rgb(var(--color-border))" strokeWidth="2" strokeLinecap="round" />
      <path d="M40 18C40 16.9 40.9 16 42 16H54C55.1 16 56 16.9 56 18V22H40V18Z" fill="rgb(var(--color-surface-3))" stroke="rgb(var(--color-border))" strokeWidth="1.5" />
      <line x1="42" y1="34" x2="42" y2="60" stroke="rgb(var(--color-muted))" strokeWidth="1.5" strokeLinecap="round" opacity="0.5" />
      <line x1="48" y1="34" x2="48" y2="60" stroke="rgb(var(--color-muted))" strokeWidth="1.5" strokeLinecap="round" opacity="0.5" />
      <line x1="54" y1="34" x2="54" y2="60" stroke="rgb(var(--color-muted))" strokeWidth="1.5" strokeLinecap="round" opacity="0.5" />
    </svg>
  ),
  search: (
    <svg width="96" height="80" viewBox="0 0 96 80" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden>
      <ellipse cx="48" cy="72" rx="32" ry="4" fill="rgb(var(--color-surface-3))" opacity="0.5" />
      <circle cx="42" cy="36" r="18" fill="none" stroke="rgb(var(--color-border))" strokeWidth="2.5" />
      <line x1="55" y1="49" x2="66" y2="60" stroke="rgb(var(--color-border))" strokeWidth="3" strokeLinecap="round" />
      <circle cx="42" cy="36" r="10" fill="rgb(var(--color-primary-100))" opacity="0.4" />
      <path d="M38 33C38 31 39.5 29.5 42 29.5" stroke="rgb(var(--color-primary-400))" strokeWidth="1.5" strokeLinecap="round" opacity="0.6" />
    </svg>
  ),
  star: (
    <svg width="96" height="80" viewBox="0 0 96 80" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden>
      <ellipse cx="48" cy="72" rx="32" ry="4" fill="rgb(var(--color-surface-3))" opacity="0.5" />
      <path d="M48 20L53 35L69 35L56 44L61 59L48 50L35 59L40 44L27 35L43 35Z" fill="rgb(var(--color-amber-100))" stroke="rgb(var(--color-amber-400))" strokeWidth="1.5" strokeLinejoin="round" />
      <path d="M48 28L51 36L59 36L53 41L55 49L48 44L41 49L43 41L37 36L45 36Z" fill="rgb(var(--color-amber-400))" opacity="0.4" />
    </svg>
  ),
  generic: (
    <svg width="96" height="80" viewBox="0 0 96 80" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden>
      <ellipse cx="48" cy="72" rx="32" ry="4" fill="rgb(var(--color-surface-3))" opacity="0.5" />
      <rect x="28" y="18" width="40" height="48" rx="4" fill="rgb(var(--color-surface-3))" stroke="rgb(var(--color-border))" strokeWidth="1.5" />
      <line x1="36" y1="32" x2="60" y2="32" stroke="rgb(var(--color-muted))" strokeWidth="1.5" strokeLinecap="round" opacity="0.5" />
      <line x1="36" y1="42" x2="60" y2="42" stroke="rgb(var(--color-muted))" strokeWidth="1.5" strokeLinecap="round" opacity="0.5" />
      <line x1="36" y1="52" x2="52" y2="52" stroke="rgb(var(--color-muted))" strokeWidth="1.5" strokeLinecap="round" opacity="0.5" />
    </svg>
  ),
};

export default function EmptyState({ type = 'generic', title, description, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center py-20 px-4">
      <div className="mb-5">{ILLUSTRATIONS[type]}</div>
      <h3 className="text-base font-semibold text-fg mb-1">{title}</h3>
      {description && <p className="text-sm text-muted mb-5 text-center max-w-xs">{description}</p>}
      {action && <div>{action}</div>}
    </div>
  );
}
