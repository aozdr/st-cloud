import { useState, useEffect, useCallback } from 'react';
import { Search, Calendar as CalendarIcon, ChevronDown, ChevronRight } from 'lucide-react';
import { format } from 'date-fns';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Card } from '../ui/card';
import { Badge } from '../ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '../ui/popover';
import { Calendar } from '../ui/calendar';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../ui/table';
import api from '../../lib/api';
import { formatSize } from '../../lib/utils';
import TimeWheelPicker from '../ui/time-wheel-picker';
import type { AuditLog, AuditLogDetail, AuditLogFileDetail, PageResult } from '../../types';

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

function ExpandedAuditDetail({ detail }: { detail: AuditLogDetail | null }) {
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

type AuditDateRange = { from: Date; to: Date | undefined };

function DateTimeRangePanel({
  dateRange,
  onDateRangeChange,
  startTime,
  onStartTimeChange,
  endTime,
  onEndTimeChange,
  onConfirm,
}: {
  dateRange: AuditDateRange | undefined;
  onDateRangeChange: (r: AuditDateRange | undefined) => void;
  startTime: string;
  onStartTimeChange: (v: string) => void;
  endTime: string;
  onEndTimeChange: (v: string) => void;
  onConfirm: () => void;
}) {
  const [localRange, setLocalRange] = useState<AuditDateRange | undefined>(dateRange);
  const [localStart, setLocalStart] = useState(startTime);
  const [localEnd, setLocalEnd] = useState(endTime);

  const hasBoth = !!(localRange?.from && localRange?.to);

  function applyQuick(start: Date, end: Date) {
    setLocalRange({ from: start, to: end });
    setLocalStart('00:00:00');
    setLocalEnd('23:59:59');
  }

  return (
    <div className="bg-surface rounded-lg flex flex-col" style={{ width: 720 }}>
      {/* main: calendar left + time right */}
      <div className="flex">
        {/* left: calendar */}
        <div className="p-3 border-r border-border">
          <Calendar
            mode="range"
            selected={localRange}
            onSelect={(range) => {
              if (range?.from) {
                setLocalRange({ from: range.from, to: range.to });
              } else {
                setLocalRange(undefined);
              }
            }}
            numberOfMonths={1}
          />
        </div>

        {/* right: time pickers */}
        <div className="p-3 flex-1 min-w-[260px]">
          {hasBoth ? (
            <div className="flex flex-col h-full justify-center gap-4">
              {/* start time */}
              <div className="flex flex-col items-center">
                <div className="text-xs font-medium text-muted mb-1 flex items-center gap-1">
                  <span className="w-1.5 h-1.5 rounded-full bg-primary-500" />
                  开始时间
                </div>
                <div className="text-xs text-muted mb-1">{format(localRange!.from!, 'yyyy-MM-dd')}</div>
                <TimeWheelPicker value={localStart} onChange={setLocalStart} />
              </div>

              <div className="flex items-center justify-center">
                <span className="text-muted text-sm">{'↓'}</span>
              </div>

              {/* end time */}
              <div className="flex flex-col items-center">
                <div className="text-xs font-medium text-muted mb-1 flex items-center gap-1">
                  <span className="w-1.5 h-1.5 rounded-full bg-muted" />
                  结束时间
                </div>
                <div className="text-xs text-muted mb-1">{format(localRange!.to!, 'yyyy-MM-dd')}</div>
                <TimeWheelPicker value={localEnd} onChange={setLocalEnd} />
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center h-full text-center py-8">
              <CalendarIcon className="w-8 h-8 text-muted/40 mb-2" />
              <p className="text-xs text-muted">请在左侧日历中选择</p>
              <p className="text-xs text-muted">开始和结束日期</p>
            </div>
          )}
        </div>
      </div>

      {/* footer */}
      <div className="flex items-center justify-between border-t border-border px-4 py-2.5">
        <button
          onClick={() => {
            setLocalRange(undefined);
            setLocalStart('00:00:00');
            setLocalEnd('23:59:59');
          }}
          className="text-xs text-muted hover:text-fg cursor-pointer transition-colors"
        >
          清除
        </button>
        <div className="flex items-center gap-2">
          <button
            onClick={() => {
              const now = new Date();
              applyQuick(now, now);
            }}
            className="text-xs text-primary-500 hover:text-primary-600 cursor-pointer px-2 py-1 rounded hover:bg-primary-500/10 transition-colors"
          >
            今天
          </button>
          <button
            onClick={() => {
              const end = new Date();
              const start = new Date();
              start.setDate(start.getDate() - 6);
              applyQuick(start, end);
            }}
            className="text-xs text-primary-500 hover:text-primary-600 cursor-pointer px-2 py-1 rounded hover:bg-primary-500/10 transition-colors"
          >
            近7天
          </button>
          <button
            onClick={() => {
              const end = new Date();
              const start = new Date();
              start.setDate(start.getDate() - 29);
              applyQuick(start, end);
            }}
            className="text-xs text-primary-500 hover:text-primary-600 cursor-pointer px-2 py-1 rounded hover:bg-primary-500/10 transition-colors"
          >
            近30天
          </button>
          <div className="w-px h-4 bg-surface-2 mx-1" />
          <Button
            variant="outline"
            size="sm"
            onClick={() => { setLocalRange(dateRange); setLocalStart(startTime); setLocalEnd(endTime); onConfirm(); }}
            className="h-7"
          >
            取消
          </Button>
          <Button
            size="sm"
            onClick={() => {
              onDateRangeChange(localRange);
              onStartTimeChange(localStart);
              onEndTimeChange(localEnd);
              onConfirm();
            }}
            className="h-7"
          >
            确定
          </Button>
        </div>
      </div>
    </div>
  );

}

function AuditTableRow({ log }: { log: AuditLog }) {
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

export default function AuditLogPanel() {
  const [auditLogs, setAuditLogs] = useState<AuditLog[]>([]);
  const [auditTotal, setAuditTotal] = useState(0);
  const [auditPage, setAuditPage] = useState(1);
  const [auditAction, setAuditAction] = useState('');
  const [auditUser, setAuditUser] = useState('');
  const [auditStatus, setAuditStatus] = useState('');
  const [auditKeyword, setAuditKeyword] = useState('');
  const [auditIp, setAuditIp] = useState('');
  const [auditTargetName, setAuditTargetName] = useState('');
  const [auditDateRange, setAuditDateRange] = useState<AuditDateRange | undefined>(undefined);
  const [auditStartTime, setAuditStartTime] = useState('00:00:00');
  const [auditEndTime, setAuditEndTime] = useState('23:59:59');
  const [searchTrigger, setSearchTrigger] = useState(0);
  const [auditPopoverOpen, setAuditPopoverOpen] = useState(false);
  const [auditSort, setAuditSort] = useState('desc');

  const fetchAuditLogs = useCallback(async () => {
    try {
      const params: Record<string, string | number> = { page: auditPage, size: 20 };
      if (auditAction) params.action = auditAction;
      if (auditUser) params.username = auditUser;
      if (auditStatus !== '') params.status = auditStatus;
      if (auditKeyword) params.keyword = auditKeyword;
      if (auditIp) params.ipAddress = auditIp;
      if (auditTargetName) params.targetName = auditTargetName;
      if (auditSort) params.sort = auditSort;
      if (auditDateRange?.from) {
        params.startTime = `${format(auditDateRange.from, 'yyyy-MM-dd')} ${auditStartTime}`;
      }
      if (auditDateRange?.to) {
        params.endTime = `${format(auditDateRange.to, 'yyyy-MM-dd')} ${auditEndTime}`;
      }
      const data: PageResult<AuditLog> = await api.get('/admin/audit/list', { params });
      setAuditLogs(data.records || []);
      setAuditTotal(parseInt(data.total) || 0);
    } catch { /* ignore */ }
  }, [auditPage, auditAction, auditUser, auditStatus, auditKeyword, auditIp, auditTargetName, auditDateRange, auditStartTime, auditEndTime, auditSort]);

  useEffect(() => {
    fetchAuditLogs();
  }, [fetchAuditLogs, searchTrigger]);

  return (
          <div className="space-y-4">
            {/* Filter bar */}
            <Card className="p-4">
              <div className="flex flex-wrap items-center gap-3">
                {/* 操作类型 */}
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted whitespace-nowrap">操作类型</span>
                  <Select value={auditAction || 'all'} onValueChange={(v) => setAuditAction(v === 'all' ? '' : v)}>
                    <SelectTrigger className="w-[160px] h-8"><SelectValue placeholder="全部" /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">全部</SelectItem>
                      {ACTION_FILTER_GROUPS.map((group) => group.actions.map((act) => (
                        <SelectItem key={act} value={act}>{ACTION_LABELS[act] || act}</SelectItem>
                      )))}
                    </SelectContent>
                  </Select>
                </div>

                {/* 状态 */}
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted whitespace-nowrap">状态</span>
                  <Select value={auditStatus !== '' ? auditStatus : 'all'} onValueChange={(v) => setAuditStatus(v === 'all' ? '' : v)}>
                    <SelectTrigger className="w-[100px] h-8"><SelectValue placeholder="全部" /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">全部</SelectItem>
                      <SelectItem value="1">成功</SelectItem>
                      <SelectItem value="0">失败</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                {/* 排序 */}
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted whitespace-nowrap">排序</span>
                  <Select value={auditSort} onValueChange={(v) => { setAuditSort(v); setAuditPage(1); }}>
                    <SelectTrigger className="w-[140px] h-8 text-xs overflow-hidden"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="desc">最新优先</SelectItem>
                      <SelectItem value="asc">最早优先</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                {/* 用户 */}
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted whitespace-nowrap">用户</span>
                  <Input
                    value={auditUser}
                    onChange={(e) => setAuditUser(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') { setAuditPage(1); setSearchTrigger(t => t + 1); } }}
                    placeholder="搜索用户名"
                    className="w-[140px] h-8"
                  />
                </div>

                {/* IP */}
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted whitespace-nowrap">IP</span>
                  <Input
                    value={auditIp}
                    onChange={(e) => setAuditIp(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') { setAuditPage(1); setSearchTrigger(t => t + 1); } }}
                    placeholder="IP 地址"
                    className="w-[140px] h-8"
                  />
                </div>

                {/* 目标名称 */}
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted whitespace-nowrap">目标名称</span>
                  <Input
                    value={auditTargetName}
                    onChange={(e) => setAuditTargetName(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') { setAuditPage(1); setSearchTrigger(t => t + 1); } }}
                    placeholder="文件/文件夹名"
                    className="w-[160px] h-8"
                  />
                </div>

                {/* 关键词 */}
                <div className="flex items-center gap-2 flex-1 min-w-[200px]">
                  <span className="text-xs text-muted whitespace-nowrap">关键词</span>
                  <Input
                    value={auditKeyword}
                    onChange={(e) => setAuditKeyword(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') { setAuditPage(1); setSearchTrigger(t => t + 1); } }}
                    placeholder="搜索详情、文件名、路径..."
                    className="flex-1 min-w-[150px] h-8"
                  />
                </div>

                {/* 时间范围 - Calendar + TimeWheelPicker */}
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted whitespace-nowrap">时间</span>
                  <Popover open={auditPopoverOpen} onOpenChange={setAuditPopoverOpen}>
                    <PopoverTrigger asChild>
                      <Button variant="outline" size="sm" className="h-8 w-[340px] justify-start text-left font-normal">
                        <CalendarIcon className="mr-2 h-4 w-4 text-muted" />
                        {auditDateRange?.from ? (
                          auditDateRange.to ? (
                            <span className="truncate">
                              {format(auditDateRange.from, 'MM/dd')} {auditStartTime}
                              <span className="mx-1 text-muted">→</span>
                              {format(auditDateRange.to, 'MM/dd')} {auditEndTime}
                            </span>
                          ) : (
                            <span className="truncate">
                              {format(auditDateRange.from, 'MM/dd')} {auditStartTime}
                              <span className="mx-1 text-muted">→</span>
                              <span className="text-muted">待选</span>
                            </span>
                          )
                        ) : (
                          <span className="text-muted">选择日期时间范围</span>
                        )}
                      </Button>
                    </PopoverTrigger>
                    <PopoverContent className="w-auto p-0" align="start" sideOffset={6}>
                      <DateTimeRangePanel
                        dateRange={auditDateRange}
                        onDateRangeChange={setAuditDateRange}
                        startTime={auditStartTime}
                        onStartTimeChange={setAuditStartTime}
                        endTime={auditEndTime}
                        onEndTimeChange={setAuditEndTime}
                        onConfirm={() => setAuditPopoverOpen(false)}
                      />
                    </PopoverContent>
                  </Popover>
                </div>

                {/* 搜索 / 重置 */}
                <div className="flex items-center gap-2">
                  <Button size="sm" onClick={() => { setAuditPage(1); setSearchTrigger(t => t + 1); }}>
                    <Search className="mr-1.5 h-4 w-4" />搜索
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => {
                    setAuditAction(''); setAuditUser(''); setAuditStatus('');
                    setAuditKeyword(''); setAuditIp(''); setAuditTargetName('');
                    setAuditDateRange(undefined);
                    setAuditStartTime('00:00:00'); setAuditEndTime('23:59:59');
                    setAuditSort('desc'); setAuditPage(1); setSearchTrigger(t => t + 1);
                  }}>
                    重置
                  </Button>
                </div>
              </div>
            </Card>

            {/* Audit table */}
            <Card>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-8"></TableHead>
                    <TableHead className="w-[170px]">时间</TableHead>
                    <TableHead className="w-[100px]">用户</TableHead>
                    <TableHead className="w-[120px]">操作</TableHead>
                    <TableHead className="w-[160px]">目标</TableHead>
                    <TableHead className="w-[280px]">详情摘要</TableHead>
                    <TableHead className="w-[130px]">IP</TableHead>
                    <TableHead className="text-center w-[80px]">状态</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {auditLogs.map((log) => (
                    <AuditTableRow key={log.id} log={log} />
                  ))}
                </TableBody>
              </Table>
              {auditLogs.length === 0 && (
                <div className="py-10 text-center text-sm text-muted">暂无符合条件的审计日志</div>
              )}
              {auditTotal > 20 && (
                <div className="flex items-center justify-between px-4 py-3 border-t border-border">
                  <span className="text-xs text-muted">共 {auditTotal} 条</span>
                  <div className="flex gap-2">
                    <Button variant="outline" size="sm" disabled={auditPage === 1} onClick={() => setAuditPage(p => Math.max(1, p - 1))}>上一页</Button>
                    <span className="px-3 py-1 text-xs text-muted">{auditPage}</span>
                    <Button variant="outline" size="sm" disabled={auditLogs.length < 20} onClick={() => setAuditPage(p => p + 1)}>下一页</Button>
                  </div>
                </div>
              )}
            </Card>
          </div>
  );
}
