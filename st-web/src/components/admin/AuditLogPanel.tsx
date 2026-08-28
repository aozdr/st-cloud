import { useState, useEffect, useCallback } from 'react';
import { Search, Calendar as CalendarIcon } from 'lucide-react';
import { format } from 'date-fns';
import { Card } from '../ui/card';
import { Popover, PopoverContent, PopoverTrigger } from '../ui/popover';
import type { AuditLog, PageResult } from '../../types';
import type { AuditDateRange } from './audit/ExpandedAuditDetail';
import { ACTION_FILTER_GROUPS, ACTION_LABELS } from './audit/audit-constants';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { Table, TableBody, TableHead, TableHeader, TableRow } from '../ui/table';
import api from '../../lib/api';

import DateTimeRangePanel from './audit/DateTimeRangePanel';
import AuditTableRow from './audit/AuditTableRow';

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
