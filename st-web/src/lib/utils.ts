import { type ClassValue, clsx } from "clsx"
import { twMerge } from "tailwind-merge"
import {
  File as FileIcon,
  Image as ImageIcon,
  Video,
  Music,
  FileText,
  Archive,
  FileType,
  Presentation,
  Sheet,
} from 'lucide-react';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
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
  { exts: IMAGE_EXTS,   config: { icon: ImageIcon,    color: 'text-green-500',  bgColor: 'bg-green-50',  label: '图片' } },
  { exts: VIDEO_EXTS,   config: { icon: Video,         color: 'text-purple-500', bgColor: 'bg-purple-50', label: '视频' } },
  { exts: AUDIO_EXTS,   config: { icon: Music,         color: 'text-pink-500',   bgColor: 'bg-pink-50',   label: '音频' } },
  { exts: PDF_EXTS,     config: { icon: FileText,      color: 'text-red-500',    bgColor: 'bg-red-50',    label: 'PDF' } },
  { exts: WORD_EXTS,    config: { icon: FileText,      color: 'text-blue-500',   bgColor: 'bg-blue-50',   label: 'Word' } },
  { exts: EXCEL_EXTS,   config: { icon: Sheet,         color: 'text-emerald-600', bgColor: 'bg-emerald-50', label: 'Excel' } },
  { exts: PPT_EXTS,     config: { icon: Presentation,  color: 'text-orange-500',  bgColor: 'bg-orange-50',  label: 'PPT' } },
  { exts: ARCHIVE_EXTS, config: { icon: Archive,       color: 'text-amber-500',  bgColor: 'bg-amber-50',  label: '压缩包' } },
  { exts: TEXT_EXTS,    config: { icon: FileText,      color: 'text-stone-500',  bgColor: 'bg-stone-50',  label: '文档' } },
];

export function getFileTypeConfig(nodeType: number, suffix: string | null | undefined): FileTypeConfig {
  if (nodeType === 0) {
    return { icon: FileIcon, color: 'text-amber-400', bgColor: 'bg-amber-50', label: '文件夹' };
  }
  if (!suffix) {
    return { icon: FileIcon, color: 'text-stone-400', bgColor: 'bg-stone-50', label: '文件' };
  }
  const ext = suffix.toLowerCase();
  for (const entry of FILE_TYPE_MAP) {
    if (entry.exts.includes(ext)) return entry.config;
  }
  return { icon: FileIcon, color: 'text-stone-400', bgColor: 'bg-stone-50', label: '文件'  };
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

export function isText(suffix: string | null | undefined): boolean {
  if (!suffix) return false;
  return TEXT_EXTS.includes(suffix.toLowerCase());
}

export function isPreviewable(suffix: string | null | undefined): boolean {
  return isImage(suffix) || isVideo(suffix) || isAudio(suffix) || isPdf(suffix) || isWord(suffix) || isExcel(suffix) || isText(suffix);
}

// Also support isImage(file) where file has a suffix property
export function isImageFile(file: { suffix?: string | null } | string | null | undefined): boolean {
  if (typeof file === 'string') return isImage(file);
  if (!file) return false;
  return isImage(file.suffix);
}

// ==================== Formatting ====================

export function formatSize(bytes: string | number | undefined | null): string {
  const n = typeof bytes === 'string' ? Number(bytes) : bytes;
  if (!n || n <= 0) return '0 B';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(1) + ' MB';
  return (n / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
}

export function formatDate(dateStr: string | null | undefined): string {
  if (!dateStr) return '-';
  const d = new Date(dateStr);
  if (isNaN(d.getTime())) return dateStr;
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const h = String(d.getHours()).padStart(2, '0');
  const min = String(d.getMinutes()).padStart(2, '0');
  return `${y}-${m}-${day} ${h}:${min}`;
}
