import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { Badge } from '../../ui/badge';
import { TableCell, TableRow } from '../../ui/table';
import { ACTION_LABELS, ACTION_CATEGORY, BADGE_VARIANT, parseAuditDetail } from './audit-constants';
import ExpandedAuditDetail from './ExpandedAuditDetail';
import type { AuditLog } from '../../../types';
export default function AuditTableRow({ log }: { log: AuditLog }) {
  const [isExpanded, setIsExpanded] = useState(false);
  const cat = ACTION_CATEGORY[log.action] || 'system';
  const detail = parseAuditDetail(log.detail);
  const hasFileDetails = !!(detail?.files?.length || detail?.oldName || detail?.fileName);

  return (
    <>
      <TableRow>
        <TableCell className="w-8 text-center">
          {hasFileDetails && (
            <button onClick={() => setIsExpanded(!isExpanded)} className="text-muted hover:text-fg cursor-pointer">
              {isExpanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
            </button>
          )}
        </TableCell>
        <TableCell className="text-xs text-muted whitespace-nowrap">{log.createdAt}</TableCell>
        <TableCell className="text-muted whitespace-nowrap">{log.username || '-'}</TableCell>
        <TableCell>
          <Badge variant={BADGE_VARIANT[cat] || 'gray'}>{ACTION_LABELS[log.action] || log.action}</Badge>
        </TableCell>
        <TableCell className="text-xs text-muted whitespace-nowrap max-w-[160px] truncate" title={log.targetName || ''}>
          {log.targetName || '-'}
        </TableCell>
        <TableCell className="text-xs text-muted max-w-[280px] truncate" title={detail?.summary || ''}>
          {detail?.summary || log.detail || '-'}
        </TableCell>
        <TableCell className="text-xs text-muted whitespace-nowrap">{log.ipAddress || '-'}</TableCell>
        <TableCell className="text-center">
          <Badge variant={log.status === 1 ? 'green' : 'red'}>{log.status === 1 ? '成功' : '失败'}</Badge>
        </TableCell>
      </TableRow>
      {isExpanded && (
        <TableRow>
          <TableCell colSpan={8} className="bg-surface-2/50">
            <ExpandedAuditDetail detail={detail} />
          </TableCell>
        </TableRow>
      )}
    </>
  );
}


