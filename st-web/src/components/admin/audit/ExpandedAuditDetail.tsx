import type { AuditLogDetail, AuditLogFileDetail } from '../../../types';
import { formatFileSize } from './audit-constants';

export default function ExpandedAuditDetail({ detail }: { detail: AuditLogDetail | null }) {
  if (!detail) return <div className="text-xs text-muted">无详情</div>;

  return (
    <div className="space-y-2 text-xs">
      {/* 文件列表 */}
      {detail.files && detail.files.length > 0 && (
        <div>
          <div className="text-muted font-medium mb-1">
            涉及文件（{detail.files.length} 个）{detail.targetFolder && <span className="ml-2 text-muted">→ 目标文件夹: {detail.targetFolder}</span>}
          </div>
          <div className="bg-surface rounded border border-border max-h-48 overflow-y-auto">
            <table className="w-full text-xs">
              <thead className="sticky top-0 bg-surface-2">
                <tr className="border-b border-border">
                  <th className="text-left px-3 py-1.5 font-medium text-muted">文件名</th>
                  <th className="text-left px-3 py-1.5 font-medium text-muted">路径</th>
                  <th className="text-right px-3 py-1.5 font-medium text-muted">大小</th>
                  <th className="text-center px-3 py-1.5 font-medium text-muted">类型</th>
                </tr>
              </thead>
              <tbody>
                {detail.files.map((f: AuditLogFileDetail, i: number) => (
                  <tr key={i} className="border-b border-border">
                    <td className="px-3 py-1.5 text-muted">{f.name}</td>
                    <td className="px-3 py-1.5 text-muted max-w-[300px] truncate" title={f.path}>{f.path}</td>
                    <td className="px-3 py-1.5 text-right text-muted whitespace-nowrap">{formatFileSize(f.size)}</td>
                    <td className="px-3 py-1.5 text-center">
                      <span className={f.type === 'folder' ? 'text-blue-500' : 'text-muted'}>
                        {f.type === 'folder' ? '文件夹' : f.suffix || '文件'}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* 单文件信息 */}
      {detail.fileName && !detail.files && (
        <div className="flex flex-wrap gap-x-6 gap-y-1">
          <span className="text-muted">文件名: <span className="text-muted">{detail.fileName}</span></span>
          <span className="text-muted">大小: <span className="text-muted">{formatFileSize(detail.fileSize)}</span></span>
          {detail.contentType && <span className="text-muted">类型: <span className="text-muted">{detail.contentType}</span></span>}
          {detail.path && <span className="text-muted">路径: <span className="text-muted">{detail.path}</span></span>}
        </div>
      )}

      {/* 重命名信息 */}
      {detail.oldName && detail.newName && (
        <div className="text-muted">
          原文件名: <span className="text-muted">{detail.oldName}</span>
          <span className="mx-2 text-muted">→</span>
          新文件名: <span className="text-muted">{detail.newName}</span>
          {detail.path && <span className="ml-4 text-muted">路径: {detail.path}</span>}
        </div>
      )}

      {/* 创建文件夹信息 */}
      {detail.folderName && (
        <div className="text-muted">
          文件夹名: <span className="text-muted">{detail.folderName}</span>
          {detail.parentFolder && <span className="ml-4">所在目录: <span className="text-muted">{detail.parentFolder}</span></span>}
          {detail.parentPath && <span className="ml-4 text-muted">目录路径: {detail.parentPath}</span>}
        </div>
      )}

      {/* 目标路径信息 */}
      {detail.targetPath && !detail.files && (
        <div className="text-muted">目标路径: <span className="text-muted">{detail.targetPath}</span></div>
      )}

      {/* 错误信息 */}
      {detail.error && (
        <div className="text-red-500 bg-red-500/15 rounded px-3 py-1.5">
          错误: {detail.error}
        </div>
      )}

      {/* 原始JSON（折叠态，方便调试） */}
      {!detail.files && !detail.fileName && !detail.oldName && !detail.folderName && !detail.targetPath && (
        <pre className="text-xs text-muted bg-surface-2 rounded p-2 overflow-x-auto">{JSON.stringify(detail, null, 2)}</pre>
      )}
    </div>
  );
}

export type AuditDateRange = { from: Date; to: Date | undefined };


