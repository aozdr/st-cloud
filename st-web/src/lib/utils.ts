import { type ClassValue, clsx } from "clsx"
import { twMerge } from "tailwind-merge"
import {
  File as FileIcon,
  Image as ImageIcon,
  Video,
  Music,
  FileText,
  Archive,
  Presentation,
  Sheet,
} from 'lucide-react';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * 白名单 HTML 消毒：仅保留 <em>（搜索高亮用），其余标签去标签保留文本，属性全部清除。
 * 用于渲染服务端返回的搜索高亮片段，防止文件名/内容注入 HTML（存储型 XSS）。
 */
const HIGHLIGHT_ALLOWED_TAGS = new Set(['EM']);

export function sanitizeHighlight(html: string): string {
  if (!html) return '';
  const doc = new DOMParser().parseFromString(html, 'text/html');
  for (const el of Array.from(doc.body.querySelectorAll('*'))) {
    if (HIGHLIGHT_ALLOWED_TAGS.has(el.tagName)) {
      for (const attr of Array.from(el.attributes)) el.removeAttribute(attr.name);
    } else {
      el.replaceWith(...Array.from(el.childNodes));
    }
  }
  return doc.body.innerHTML;
}

// ==================== File Type Detection ====================

export interface FileTypeConfig {
  icon: typeof FileIcon;
  color: string;
  bgColor: string;
  label: string;
}

const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'ico', 'tiff', 'tif'];
const VIDEO_EXTS = ['mp4', 'mkv', 'avi', 'mov', 'wmv', 'flv', 'webm', 'm4v', 'mpg', 'mpeg', '3gp'];
const AUDIO_EXTS = ['mp3', 'wav', 'flac', 'aac', 'ogg', 'wma', 'm4a', 'opus'];
const PDF_EXTS   = ['pdf'];
const WORD_EXTS  = ['doc', 'docx'];
const EXCEL_EXTS = ['xls', 'xlsx', 'csv'];
const PPT_EXTS   = ['ppt', 'pptx'];
const ARCHIVE_EXTS = ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'tgz'];
const TEXT_EXTS  = ['txt', 'md', 'markdown', 'log', 'json', 'xml', 'yaml', 'yml', 'ini', 'conf', 'properties', 'js', 'ts', 'jsx', 'tsx', 'py', 'java', 'c', 'cpp', 'h', 'css', 'scss', 'less', 'html', 'htm', 'go', 'rs', 'rb', 'php', 'sh', 'bat', 'sql', 'vue', 'svelte'];

const FILE_TYPE_MAP: { exts: string[]; config: FileTypeConfig }[] = [
  { exts: IMAGE_EXTS,   config: { icon: ImageIcon,    color: 'text-green-500',  bgColor: 'bg-green-500/15',  label: '图片' } },
  { exts: VIDEO_EXTS,   config: { icon: Video,         color: 'text-purple-500', bgColor: 'bg-purple-500/15', label: '视频' } },
  { exts: AUDIO_EXTS,   config: { icon: Music,         color: 'text-pink-500',   bgColor: 'bg-pink-500/15',   label: '音频' } },
  { exts: PDF_EXTS,     config: { icon: FileText,      color: 'text-red-500',    bgColor: 'bg-red-500/15',    label: 'PDF' } },
  { exts: WORD_EXTS,    config: { icon: FileText,      color: 'text-blue-500',   bgColor: 'bg-blue-500/15',   label: 'Word' } },
  { exts: EXCEL_EXTS,   config: { icon: Sheet,         color: 'text-emerald-600 dark:text-emerald-400', bgColor: 'bg-emerald-500/15', label: 'Excel' } },
  { exts: PPT_EXTS,     config: { icon: Presentation,  color: 'text-orange-500',  bgColor: 'bg-orange-500/15',  label: 'PPT' } },
  { exts: ARCHIVE_EXTS, config: { icon: Archive,       color: 'text-amber-500',  bgColor: 'bg-amber-500/15',  label: '压缩包' } },
  { exts: TEXT_EXTS,    config: { icon: FileText,      color: 'text-muted',  bgColor: 'bg-surface-2',  label: '文档' } },
];

// Pre-built O(1) lookup map: extension -> file type config
const EXT_CONFIG_MAP: Map<string, FileTypeConfig> = new Map();
for (const entry of FILE_TYPE_MAP) {
  for (const ext of entry.exts) EXT_CONFIG_MAP.set(ext, entry.config);
}

export function getFileTypeConfig(nodeType: number, suffix: string | null | undefined): FileTypeConfig {
  if (nodeType === 0) {
    return { icon: FileIcon, color: 'text-amber-400', bgColor: 'bg-amber-500/15', label: '文件夹' };
  }
  if (!suffix) {
    return { icon: FileIcon, color: 'text-muted', bgColor: 'bg-surface-2', label: '文件' };
  }
  return EXT_CONFIG_MAP.get(suffix.toLowerCase()) ?? { icon: FileIcon, color: 'text-muted', bgColor: 'bg-surface-2', label: '文件' };
}

export function isImage(suffix: string | null | undefined): boolean {
  if (!suffix) return false;
  return IMAGE_EXTS.includes(suffix.toLowerCase());
}

export function isVideo(suffix: string | null | undefined): boolean {
  if (!suffix) return false;
  return VIDEO_EXTS.includes(suffix.toLowerCase());
}

export function isAudio(suffix: string | null | undefined): boolean {
  if (!suffix) return false;
  return AUDIO_EXTS.includes(suffix.toLowerCase());
}

export function isPdf(suffix: string | null | undefined): boolean {
  if (!suffix) return false;
  return PDF_EXTS.includes(suffix.toLowerCase());
}

export function isWord(suffix: string | null | undefined): boolean {
  if (!suffix) return false;
  return WORD_EXTS.includes(suffix.toLowerCase());
}

export function isExcel(suffix: string | null | undefined): boolean {
  if (!suffix) return false;
  return EXCEL_EXTS.includes(suffix.toLowerCase());
}

export function isPpt(suffix: string | null | undefined): boolean {
  if (!suffix) return false;
  return PPT_EXTS.includes(suffix.toLowerCase());
}

export function isText(suffix: string | null | undefined): boolean {
  if (!suffix) return false;
  return TEXT_EXTS.includes(suffix.toLowerCase());
}

/** 后端在线解压仅支持 ZIP（ArchiveServiceImpl.validateZipFile） */
export function isZip(suffix: string | null | undefined): boolean {
  if (!suffix) return false;
  return suffix.toLowerCase() === 'zip';
}

export function isPreviewable(suffix: string | null | undefined): boolean {
  return isImage(suffix) || isVideo(suffix) || isAudio(suffix) || isPdf(suffix) || isWord(suffix) || isExcel(suffix) || isPpt(suffix) || isText(suffix);
}

// Also support isImage(file) where file has a suffix property
export function isImageFile(file: { suffix?: string | null } | string | null | undefined): boolean {
  if (typeof file === 'string') return isImage(file);
  if (!file) return false;
  return isImage(file.suffix);
}

// ==================== Formatting ====================

// Intl formatters (locale-aware, no grouping to keep sizes compact)
const sizeBytesFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0, useGrouping: false });
const sizeUnitFormatter = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 1, maximumFractionDigits: 1, useGrouping: false });

export function formatSize(bytes: string | number | undefined | null): string {
  const n = typeof bytes === 'string' ? Number(bytes) : bytes;
  if (!n || n <= 0) return '0 B';
  if (n < 1024) return `${sizeBytesFormatter.format(n)} B`;
  if (n < 1024 * 1024) return `${sizeUnitFormatter.format(n / 1024)} KB`;
  if (n < 1024 * 1024 * 1024) return `${sizeUnitFormatter.format(n / (1024 * 1024))} MB`;
  return `${sizeUnitFormatter.format(n / (1024 * 1024 * 1024))} GB`;
}

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

export function formatDate(dateStr: string | null | undefined): string {
  if (!dateStr) return '-';
  const d = new Date(dateStr);
  if (isNaN(d.getTime())) return dateStr;
  const parts = dateFormatter.formatToParts(d);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}`;
}
