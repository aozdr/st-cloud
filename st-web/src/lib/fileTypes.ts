export type FileTypeFilter = 'all' | 'folder' | 'image' | 'video' | 'audio' | 'document' | 'archive';

export const FILTER_SUFFIXES: Record<Exclude<FileTypeFilter, 'all' | 'folder'>, string[]> = {
  image: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'ico', 'tiff', 'tif'],
  video: ['mp4', 'mkv', 'avi', 'mov', 'wmv', 'flv', 'webm', 'm4v', 'mpg', 'mpeg', '3gp'],
  audio: ['mp3', 'wav', 'flac', 'aac', 'ogg', 'wma', 'm4a', 'opus'],
  document: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'csv', 'ppt', 'pptx', 'txt', 'md', 'markdown'],
  archive: ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'tgz'],
};

export interface FileCategory {
  type: Exclude<FileTypeFilter, 'all' | 'folder'>;
  label: string;
  suffixes: string[];
}

export const FILE_CATEGORIES: FileCategory[] = [
  { type: 'image', label: '图片', suffixes: FILTER_SUFFIXES.image },
  { type: 'video', label: '视频', suffixes: FILTER_SUFFIXES.video },
  { type: 'document', label: '文档', suffixes: FILTER_SUFFIXES.document },
  { type: 'audio', label: '音乐', suffixes: FILTER_SUFFIXES.audio },
  { type: 'archive', label: '压缩包', suffixes: FILTER_SUFFIXES.archive },
];