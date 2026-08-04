import { useState, useEffect, useCallback } from 'react';
import { Settings, Users, HardDrive, FileText, Share2, Activity, Ban, Key, Trash2, Shield, ChevronDown, ChevronRight, Search, Calendar as CalendarIcon, Edit3 } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts';
import { format } from 'date-fns';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Calendar } from '@/components/ui/calendar';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import api from '../lib/api';
import { useToast } from '../components/ui/Toast';
import { usePrompt } from '../components/ui/PromptDialog';
import { formatSize, cn } from '../lib/utils';
import type { StatsVO, AdminUser, AuditLog, AuditLogDetail, AuditLogFileDetail, PageResult } from '../types';
import SpeedLimitPanel from '../components/admin/SpeedLimitPanel';
import { useStorageStore } from '../store/storage';

const ACTION_LABELS: Record<string, string> = {
  CREATE_FOLDER: '创建文件夹',
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

const CATEGORY_STYLE: Record<string, string> = {
  file:   'text-blue-600 bg-blue-50',
  delete: 'text-red-600 bg-red-50',
  share:  'text-amber-600 bg-amber-50',
  team:   'text-purple-600 bg-purple-50',
  auth:   'text-green-600 bg-green-50',
  sync:   'text-cyan-600 bg-cyan-50',
  system: 'text-stone-600 bg-stone-100',
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

type Tab = 'dashboard' | 'users' | 'storage' | 'audit' | 'speedLimit';

/** 解析审计日志的JSON detail字段 */
function parseAuditDetail(detail: string | null): AuditLogDetail | null {
  if (!detail) return null;
  try {
    return JSON.parse(detail) as AuditLogDetail;
  } catch {
    // 旧格式纯文本，包装为summary
    return { summary: detail };
  }
}

/** 格式化文件大小 */
function formatFileSize(bytes: number | undefined): string {
  if (!bytes || bytes <= 0) return '-';
  return formatSize(bytes);
}

/** 展开的审计日志详情行组件 */
function ExpandedAuditDetail({ detail }: { detail: AuditLogDetail | null }) {
  if (!detail) return <div className="text-xs text-stone-400">无详情</div>;

  return (
    <div className="space-y-2 text-xs">
      {/* 文件列表 */}
      {detail.files && detail.files.length > 0 && (
        <div>
          <div className="text-stone-500 font-medium mb-1">
            涉及文件（{detail.files.length} 个）{detail.targetFolder && <span className="ml-2 text-stone-400">→ 目标文件夹: {detail.targetFolder}</span>}
          </div>
          <div className="bg-white rounded border border-stone-200 max-h-48 overflow-y-auto">
            <table className="w-full text-xs">
              <thead className="sticky top-0 bg-stone-50">
                <tr className="border-b border-stone-200">
                  <th className="text-left px-3 py-1.5 font-medium text-stone-400">文件名</th>
                  <th className="text-left px-3 py-1.5 font-medium text-stone-400">路径</th>
                  <th className="text-right px-3 py-1.5 font-medium text-stone-400">大小</th>
                  <th className="text-center px-3 py-1.5 font-medium text-stone-400">类型</th>
                </tr>
              </thead>
              <tbody>
                {detail.files.map((f: AuditLogFileDetail, i: number) => (
                  <tr key={i} className="border-b border-stone-50">
                    <td className="px-3 py-1.5 text-stone-700">{f.name}</td>
                    <td className="px-3 py-1.5 text-stone-400 max-w-[300px] truncate" title={f.path}>{f.path}</td>
                    <td className="px-3 py-1.5 text-right text-stone-500 whitespace-nowrap">{formatFileSize(f.size)}</td>
                    <td className="px-3 py-1.5 text-center">
                      <span className={f.type === 'folder' ? 'text-blue-500' : 'text-stone-500'}>
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
          <span className="text-stone-500">文件名: <span className="text-stone-700">{detail.fileName}</span></span>
          <span className="text-stone-500">大小: <span className="text-stone-700">{formatFileSize(detail.fileSize)}</span></span>
          {detail.contentType && <span className="text-stone-500">类型: <span className="text-stone-700">{detail.contentType}</span></span>}
          {detail.path && <span className="text-stone-500">路径: <span className="text-stone-700">{detail.path}</span></span>}
        </div>
      )}

      {/* 重命名信息 */}
      {detail.oldName && detail.newName && (
        <div className="text-stone-500">
          原文件名: <span className="text-stone-700">{detail.oldName}</span>
          <span className="mx-2 text-stone-300">→</span>
          新文件名: <span className="text-stone-700">{detail.newName}</span>
          {detail.path && <span className="ml-4 text-stone-400">路径: {detail.path}</span>}
        </div>
      )}

      {/* 创建文件夹信息 */}
      {detail.folderName && (
        <div className="text-stone-500">
          文件夹名: <span className="text-stone-700">{detail.folderName}</span>
          {detail.parentFolder && <span className="ml-4">所在目录: <span className="text-stone-700">{detail.parentFolder}</span></span>}
          {detail.parentPath && <span className="ml-4 text-stone-400">目录路径: {detail.parentPath}</span>}
        </div>
      )}

      {/* 目标路径信息 */}
      {detail.targetPath && !detail.files && (
        <div className="text-stone-500">目标路径: <span className="text-stone-700">{detail.targetPath}</span></div>
      )}

      {/* 错误信息 */}
      {detail.error && (
        <div className="text-red-500 bg-red-50 rounded px-3 py-1.5">
          错误: {detail.error}
        </div>
      )}

      {/* 原始JSON（折叠态，方便调试） */}
      {!detail.files && !detail.fileName && !detail.oldName && !detail.folderName && !detail.targetPath && (
        <pre className="text-xs text-stone-400 bg-stone-100 rounded p-2 overflow-x-auto">{JSON.stringify(detail, null, 2)}</pre>
      )}
    </div>
  );
}

import TimeWheelPicker from '@/components/ui/time-wheel-picker';

type AuditDateRange = { from: Date; to: Date | undefined };

/**
 * 日期时间范围选择面板（带确认按钮）
 * 流程：日历选开始+结束日期 -> 选择开始/结束时间 -> 点击确定
 */
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
    <div className="bg-white rounded-lg flex flex-col" style={{ width: 720 }}>
      {/* main: calendar left + time right */}
      <div className="flex">
        {/* left: calendar */}
        <div className="p-3 border-r border-stone-100">
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
                <div className="text-xs font-medium text-stone-500 mb-1 flex items-center gap-1">
                  <span className="w-1.5 h-1.5 rounded-full bg-primary-500" />
                  开始时间
                </div>
                <div className="text-xs text-stone-400 mb-1">{format(localRange!.from!, 'yyyy-MM-dd')}</div>
                <TimeWheelPicker value={localStart} onChange={setLocalStart} />
              </div>

              <div className="flex items-center justify-center">
                <span className="text-stone-300 text-sm">{'↓'}</span>
              </div>

              {/* end time */}
              <div className="flex flex-col items-center">
                <div className="text-xs font-medium text-stone-500 mb-1 flex items-center gap-1">
                  <span className="w-1.5 h-1.5 rounded-full bg-stone-400" />
                  结束时间
                </div>
                <div className="text-xs text-stone-400 mb-1">{format(localRange!.to!, 'yyyy-MM-dd')}</div>
                <TimeWheelPicker value={localEnd} onChange={setLocalEnd} />
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center h-full text-center py-8">
              <CalendarIcon className="w-8 h-8 text-stone-200 mb-2" />
              <p className="text-xs text-stone-400">请在左侧日历中选择</p>
              <p className="text-xs text-stone-400">开始和结束日期</p>
            </div>
          )}
        </div>
      </div>

      {/* footer */}
      <div className="flex items-center justify-between border-t border-stone-100 px-4 py-2.5">
        <button
          onClick={() => {
            setLocalRange(undefined);
            setLocalStart('00:00:00');
            setLocalEnd('23:59:59');
          }}
          className="text-xs text-stone-400 hover:text-stone-600 cursor-pointer transition-colors"
        >
          清除
        </button>
        <div className="flex items-center gap-2">
          <button
            onClick={() => {
              const now = new Date();
              applyQuick(now, now);
            }}
            className="text-xs text-primary-500 hover:text-primary-600 cursor-pointer px-2 py-1 rounded hover:bg-primary-50 transition-colors"
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
            className="text-xs text-primary-500 hover:text-primary-600 cursor-pointer px-2 py-1 rounded hover:bg-primary-50 transition-colors"
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
            className="text-xs text-primary-500 hover:text-primary-600 cursor-pointer px-2 py-1 rounded hover:bg-primary-50 transition-colors"
          >
            近30天
          </button>
          <div className="w-px h-4 bg-stone-200 mx-1" />
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

/** 审计日志表格行（含展开详情） */
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
            <button onClick={() => setIsExpanded(!isExpanded)} className="text-stone-400 hover:text-stone-600 cursor-pointer">
              {isExpanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
            </button>
          )}
        </TableCell>
        <TableCell className="text-xs text-stone-500 whitespace-nowrap">{log.createdAt}</TableCell>
        <TableCell className="text-stone-700 whitespace-nowrap">{log.username || '-'}</TableCell>
        <TableCell>
          <Badge variant={BADGE_VARIANT[cat] || 'gray'}>{ACTION_LABELS[log.action] || log.action}</Badge>
        </TableCell>
        <TableCell className="text-xs text-stone-600 whitespace-nowrap max-w-[160px] truncate" title={log.targetName || ''}>
          {log.targetName || '-'}
        </TableCell>
        <TableCell className="text-xs text-stone-600 max-w-[280px] truncate" title={detail?.summary || ''}>
          {detail?.summary || log.detail || '-'}
        </TableCell>
        <TableCell className="text-xs text-stone-400 whitespace-nowrap">{log.ipAddress || '-'}</TableCell>
        <TableCell className="text-center">
          <Badge variant={log.status === 1 ? 'green' : 'red'}>{log.status === 1 ? '成功' : '失败'}</Badge>
        </TableCell>
      </TableRow>
      {isExpanded && (
        <TableRow>
          <TableCell colSpan={8} className="bg-stone-50/50">
            <ExpandedAuditDetail detail={detail} />
          </TableCell>
        </TableRow>
      )}
    </>
  );
}

function QuotaEditDialog({ user, onClose, onSave }: {
  user: AdminUser;
  onClose: () => void;
  onSave: (quotaBytes: number) => void;
}) {
  const [value, setValue] = useState('');
  const [unit, setUnit] = useState('GB');

  useEffect(() => {
    const quotaNum = Number(user.storageQuota || 0);
    if (quotaNum === 0) {
      setValue('');
      setUnit('GB');
    } else {
      const gb = quotaNum / (1024 ** 3);
      if (gb >= 1) { setValue(String(Math.round(gb * 100) / 100)); setUnit('GB'); }
      else { setValue(String(Math.round(quotaNum / (1024 ** 2) * 100) / 100)); setUnit('MB'); }
    }
  }, [user]);

  const quotaBytes = value ? Math.round(parseFloat(value) * (unit === 'GB' ? 1024 ** 3 : 1024 ** 2)) : 0;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content w-[400px] max-w-[92vw] p-6" onClick={(e) => e.stopPropagation()}>
        <h2 className="text-lg font-semibold text-stone-900 mb-1">修改存储配额</h2>
        <p className="text-sm text-stone-500 mb-5">
          {user.username}（已用 {formatSize(Number(user.storageUsed))}）
        </p>

        <label className="block text-sm font-medium text-stone-700 mb-2">配额大小</label>
        <div className="flex items-center gap-2 mb-3">
          <input
            type="number"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder="输入0表示不限制"
            className="input-field flex-1"
            min="0"
            step="0.1"
          />
          <select
            value={unit}
            onChange={(e) => setUnit(e.target.value)}
            className="input-field w-20 cursor-pointer"
          >
            <option value="MB">MB</option>
            <option value="GB">GB</option>
            <option value="TB">TB</option>
          </select>
        </div>

        {value && parseFloat(value) > 0 && (
          <p className="text-xs text-stone-400 mb-2">
            = {formatSize(quotaBytes)}{unit === 'TB' ? ' (' + value + ' ' + unit + ')' : ''}
          </p>
        )}
        {!value && (
          <p className="text-xs text-amber-600 mb-2">不填写或填0表示不限制</p>
        )}

        <div className="flex flex-wrap gap-1.5 mb-5">
          {[
            { label: '5 GB', val: 5 * 1024 ** 3 },
            { label: '10 GB', val: 10 * 1024 ** 3 },
            { label: '50 GB', val: 50 * 1024 ** 3 },
            { label: '100 GB', val: 100 * 1024 ** 3 },
            { label: '1 TB', val: 1024 ** 4 },
            { label: '不限制', val: 0 },
          ].map((preset) => (
            <button
              key={preset.label}
              onClick={() => {
                if (preset.val === 0) { setValue(''); }
                else {
                  const gb = preset.val / (1024 ** 3);
                  setValue(String(Math.round(gb * 100) / 100));
                  setUnit('GB');
                }
              }}
              className="px-2.5 py-1 text-xs text-stone-600 bg-stone-100 hover:bg-primary-50 hover:text-primary-600 rounded-md cursor-pointer transition-colors"
            >
              {preset.label}
            </button>
          ))}
        </div>

        <div className="flex justify-end gap-2">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={() => onSave(quotaBytes)} className="btn-primary">保存</button>
        </div>
      </div>
    </div>
  );
}

function CloudCapacityEditDialog({ currentCapacity, used, onClose, onSave }: {
  currentCapacity: number | null;
  used: number;
  onClose: () => void;
  onSave: (capacityBytes: number) => void;
}) {
  const [value, setValue] = useState("");
  const [unit, setUnit] = useState("GB");

  useEffect(() => {
    const capNum = Number(currentCapacity || 0);
    if (capNum === 0) {
      setValue("");
      setUnit("GB");
    } else {
      const gb = capNum / (1024 ** 3);
      if (gb >= 1) { setValue(String(Math.round(gb * 100) / 100)); setUnit("GB"); }
      else { setValue(String(Math.round(capNum / (1024 ** 2) * 100) / 100)); setUnit("MB"); }
    }
  }, [currentCapacity]);

  const capacityBytes = value ? Math.round(parseFloat(value) * (unit === "TB" ? 1024 ** 4 : unit === "GB" ? 1024 ** 3 : 1024 ** 2)) : 0;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content w-[400px] max-w-[92vw] p-6" onClick={(e) => e.stopPropagation()}>
        <h2 className="text-lg font-semibold text-stone-900 mb-1">云盘总容量</h2>
        <p className="text-sm text-stone-500 mb-5">当前已用 {formatSize(used)}</p>

        <label className="block text-sm font-medium text-stone-700 mb-2">总容量</label>
        <div className="flex items-center gap-2 mb-3">
          <input
            type="number"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder="输入0表示不限制"
            className="input-field flex-1"
            min="0"
            step="0.1"
          />
          <select value={unit} onChange={(e) => setUnit(e.target.value)} className="input-field w-20 cursor-pointer">
            <option value="MB">MB</option>
            <option value="GB">GB</option>
            <option value="TB">TB</option>
          </select>
        </div>

        {value && parseFloat(value) > 0 && (
          <p className="text-xs text-stone-400 mb-2">= {formatSize(capacityBytes)}</p>
        )}
        {!value && (
          <p className="text-xs text-amber-600 mb-2">不填写或填0表示不限制</p>
        )}

        <div className="flex flex-wrap gap-1.5 mb-5">
          {[
            { label: "50 GB", val: 50 * 1024 ** 3 },
            { label: "100 GB", val: 100 * 1024 ** 3 },
            { label: "500 GB", val: 500 * 1024 ** 3 },
            { label: "1 TB", val: 1024 ** 4 },
            { label: "2 TB", val: 2 * 1024 ** 4 },
            { label: "不限制", val: 0 },
          ].map((preset) => (
            <button
              key={preset.label}
              onClick={() => {
                if (preset.val === 0) { setValue(""); }
                else {
                  const gb = preset.val / (1024 ** 3);
                  setValue(String(Math.round(gb * 100) / 100));
                  setUnit("GB");
                }
              }}
              className="px-2.5 py-1 text-xs text-stone-600 bg-stone-100 hover:bg-primary-50 hover:text-primary-600 rounded-md cursor-pointer transition-colors"
            >
              {preset.label}
            </button>
          ))}
        </div>

        <div className="flex justify-end gap-2">
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={() => onSave(capacityBytes)} className="btn-primary">保存</button>
        </div>
      </div>
    </div>
  );
}

export default function AdminPage() {
  const { showToast } = useToast();
  const { prompt } = usePrompt();
  const [tab, setTab] = useState<Tab>('dashboard');
  const [stats, setStats] = useState<StatsVO | null>(null);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [userTotal, setUserTotal] = useState(0);
  const [userPage, setUserPage] = useState(1);
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
  const [quotaTarget, setQuotaTarget] = useState<AdminUser | null>(null);
  const [cloudCapacityOpen, setCloudCapacityOpen] = useState(false);
  const [auditSort, setAuditSort] = useState('desc');

  const fetchStats = useCallback(async () => {
    try {
      const data: StatsVO = await api.get('/admin/stats');
      setStats(data);
    } catch { /* ignore */ }
  }, []);

  const fetchUsers = useCallback(async () => {
    try {
      const data: PageResult<AdminUser> = await api.get('/admin/user/list', { params: { page: userPage, size: 20 } });
      setUsers(data.records || []);
      setUserTotal(parseInt(data.total) || 0);
    } catch { /* ignore */ }
  }, [userPage]);

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
    fetchStats();
  }, [fetchStats]);

  useEffect(() => {
    if (tab === 'users') fetchUsers();
  }, [tab, fetchUsers]);

  useEffect(() => {
    if (tab === 'audit') fetchAuditLogs();
  }, [tab, fetchAuditLogs, searchTrigger]);

  useEffect(() => {
    if (tab === 'storage') fetchUsers();
  }, [tab, fetchUsers]);

  const handleToggleStatus = async (userId: string, currentStatus: number) => {
    try {
      await api.put(`/admin/user/${userId}`, { status: currentStatus === 1 ? 0 : 1 });
      showToast(currentStatus === 1 ? '用户已禁用' : '用户已启用', 'success');
      fetchUsers();
    } catch (e: any) {
      showToast(e.message || '操作失败', 'error');
    }
  };

  const handleResetPassword = async (userId: string) => {
    const pwd = await prompt({ title: '重置密码', message: '请输入新密码', placeholder: '输入新密码' });
    if (!pwd) return;
    try {
      await api.put(`/admin/user/${userId}`, { resetPassword: pwd });
      showToast('密码已重置', 'success');
    } catch (e: any) {
      showToast(e.message || '操作失败', 'error');
    }
  };

  const handleUpdateQuota = async (userId: string, quotaBytes: number) => {
    try {
      await api.put(`/admin/user/${userId}`, { storageQuota: quotaBytes });
      showToast('配额已更新', 'success');
      setQuotaTarget(null);
      fetchUsers();
      useStorageStore.getState().fetchStorage();
    } catch (e: any) {
      showToast(e.message || '操作失败', 'error');
    }
  };

  const handleSaveCloudCapacity = async (capacityBytes: number) => {
    try {
      await api.put('/admin/cloud-capacity', { capacity: capacityBytes || null });
      showToast('云盘总容量已更新', 'success');
      setCloudCapacityOpen(false);
      fetchStats();
    } catch (e: any) {
      showToast(e.message || '操作失败', 'error');
    }
  };

  const statCards = stats ? [
    { label: '总用户数', value: stats.totalUsers, icon: Users },
    { label: '活跃用户(7天)', value: stats.activeUsers, icon: Activity },
    { label: '总文件数', value: stats.totalFiles, icon: FileText },
    { label: '存储用量', value: formatSize(stats.totalStorageUsed), icon: HardDrive },
    { label: '分享总数', value: stats.totalShares, icon: Share2 },
    { label: '团队空间', value: stats.totalTeams, icon: Users },
  ] : [];

  const storageData = stats ? [
    { name: '已用', value: stats.totalStorageUsed },
  ] : [];

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-stone-200 bg-white">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
            <Settings className="w-4 h-4 text-white" />
          </div>
          <h1 className="text-lg font-semibold text-stone-900">系统管理</h1>
        </div>
        {/* Tabs */}
        <div className="flex gap-1 bg-stone-100 rounded-lg p-1">
          {([
            { v: 'dashboard', label: '仪表盘' },
            { v: 'users', label: '用户管理' },
            { v: 'storage', label: '存储管理' },
            { v: 'audit', label: '审计日志' },
            { v: 'speedLimit', label: '限速管理' },
          ] as { v: Tab; label: string }[]).map((t) => (
            <button
              key={t.v}
              onClick={() => setTab(t.v)}
              className={`px-4 py-1.5 text-sm font-medium rounded-md transition-all cursor-pointer ${
                tab === t.v ? 'bg-white text-stone-900 shadow-sm' : 'text-stone-500 hover:text-stone-700'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-auto p-6">
        {/* Dashboard Tab */}
        {tab === 'dashboard' && (
          <div className="space-y-6">
            {/* Stat cards */}
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
              {statCards.map((card) => (
                <div key={card.label} className="bg-white rounded-lg border border-stone-200 p-4">
                  <div className="w-10 h-10 bg-primary-50 rounded-lg flex items-center justify-center mb-3">
                    <card.icon className="w-5 h-5 text-primary-600" />
                  </div>
                  <p className="text-2xl font-bold text-stone-900">{card.value}</p>
                  <p className="text-xs text-stone-500 mt-0.5">{card.label}</p>
                </div>
              ))}
            </div>

            {/* Charts */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <div className="bg-white rounded-lg border border-stone-200 p-5">
                <h3 className="text-sm font-semibold text-stone-700 mb-4">存储用量概览</h3>
                <ResponsiveContainer width="100%" height={200}>
                  <BarChart data={statCards.slice(0, 3).map(c => ({ name: c.label, value: c.value }))}>
                    <XAxis dataKey="name" tick={{ fontSize: 11 }} />
                    <YAxis tick={{ fontSize: 11 }} />
                    <Tooltip />
                    <Bar dataKey="value" fill="#D9272E" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
              <div className="bg-white rounded-lg border border-stone-200 p-5">
                <h3 className="text-sm font-semibold text-stone-700 mb-4">用户活跃度</h3>
                <ResponsiveContainer width="100%" height={200}>
                  <PieChart>
                    <Pie
                      data={[
                        { name: '活跃用户', value: stats?.activeUsers || 0 },
                        { name: '非活跃用户', value: (stats?.totalUsers || 0) - (stats?.activeUsers || 0) },
                      ]}
                      cx="50%" cy="50%" innerRadius={50} outerRadius={80}
                      dataKey="value"
                      label={({ name, value }) => `${name}: ${value}`}
                    >
                      <Cell fill="#22c55e" />
                      <Cell fill="#e2e8f0" />
                    </Pie>
                    <Tooltip />
                  </PieChart>
                </ResponsiveContainer>
              </div>
            </div>
          </div>
        )}

        {/* Users Tab */}
        {tab === 'users' && (
          <div className="bg-white rounded-lg border border-stone-200 overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-stone-200 bg-stone-50">
                  <th className="text-left px-4 py-3 font-medium text-stone-500">用户名</th>
                  <th className="text-left px-4 py-3 font-medium text-stone-500">昵称</th>
                  <th className="text-left px-4 py-3 font-medium text-stone-500">存储用量</th>
                  <th className="text-center px-4 py-3 font-medium text-stone-500">角色</th>
                  <th className="text-center px-4 py-3 font-medium text-stone-500">状态</th>
                  <th className="text-center px-4 py-3 font-medium text-stone-500">操作</th>
                </tr>
              </thead>
              <tbody>
                {users.map((user) => (
                  <tr key={user.id} className="border-b border-stone-100 hover:bg-stone-50 transition-colors">
                    <td className="px-4 py-3 text-stone-800 font-medium">{user.username}</td>
                    <td className="px-4 py-3 text-stone-600">{user.nickname || '-'}</td>
                    <td className="px-4 py-3 text-stone-500 text-xs">
                      {formatSize(Number(user.storageUsed))} / {formatSize(Number(user.storageQuota))}
                    </td>
                    <td className="px-4 py-3 text-center">
                      {user.isAdmin === 1 ? (
                        <span className="inline-flex items-center gap-1 text-xs text-primary-700 bg-primary-50 px-2 py-0.5 rounded-md">
                          <Shield className="w-3 h-3" /> 管理员
                        </span>
                      ) : (
                        <span className="text-xs text-stone-400">普通用户</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-center">
                      {user.status === 1 ? (
                        <span className="text-xs text-green-600 bg-green-50 px-2 py-0.5 rounded-md">正常</span>
                      ) : (
                        <span className="text-xs text-red-600 bg-red-50 px-2 py-0.5 rounded-md">禁用</span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-center gap-2">
                        <button
                          onClick={() => handleToggleStatus(user.id, user.status)}
                          className="text-stone-400 hover:text-amber-600 transition-colors cursor-pointer"
                          title={user.status === 1 ? '禁用' : '启用'}
                        >
                          <Ban className="w-4 h-4" />
                        </button>
                        <button
                          onClick={() => handleResetPassword(user.id)}
                          className="text-stone-400 hover:text-primary-600 transition-colors cursor-pointer"
                          title="重置密码"
                        >
                          <Key className="w-4 h-4" />
                        </button>
                        <button
                          onClick={() => setQuotaTarget(user)}
                          className="text-stone-400 hover:text-primary-600 transition-colors cursor-pointer"
                          title="修改配额"
                        >
                          <Edit3 className="w-4 h-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {userTotal > 20 && (
              <div className="flex items-center justify-between px-4 py-3 border-t border-stone-100">
                <span className="text-xs text-stone-400">共 {userTotal} 条</span>
                <div className="flex gap-2">
                  <button onClick={() => setUserPage(p => Math.max(1, p - 1))} disabled={userPage === 1}
                    className="px-3 py-1 text-xs text-stone-600 bg-stone-100 rounded-md hover:bg-stone-200 disabled:opacity-40 cursor-pointer transition-colors">上一页</button>
                  <span className="px-3 py-1 text-xs text-stone-500">{userPage}</span>
                  <button onClick={() => setUserPage(p => p + 1)} disabled={users.length < 20}
                    className="px-3 py-1 text-xs text-stone-600 bg-stone-100 rounded-md hover:bg-stone-200 disabled:opacity-40 cursor-pointer transition-colors">下一页</button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* Storage Tab */}
        {tab === 'storage' && (
          <div className="space-y-4">
            {/* Cloud total capacity */}
            <div className="bg-white rounded-lg border border-stone-200 p-5">
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-2">
                  <HardDrive className="w-4 h-4 text-primary-600" />
                  <span className="text-sm font-medium text-stone-700">云盘总容量</span>
                </div>
                <button onClick={() => setCloudCapacityOpen(true)} className="text-xs text-primary-600 hover:underline cursor-pointer">编辑</button>
              </div>
              {(() => {
                const used = stats?.cloudStorageUsed || 0;
                const total = stats?.cloudTotalCapacity;
                const pct = total && total > 0 ? Math.min((used / total) * 100, 100) : 0;
                const low = !!(total && total > 0 && pct > 90);
                const barClass = "h-full rounded-full transition-all " + (low ? "bg-red-500" : "bg-primary-600");
                return (
                  <>
                    <div className="w-full h-2 bg-stone-100 rounded-full overflow-hidden mb-2">
                      <div className={barClass} style={{ width: pct + "%" }} />
                    </div>
                    <div className="flex justify-between text-xs text-stone-500">
                      <span>{formatSize(used)} 已用</span>
                      <span>{total && total > 0 ? formatSize(total) + " 总量" : "不限"}</span>
                    </div>
                    {total && total > 0 && pct > 90 && (
                      <p className="mt-1.5 text-xs text-red-500 font-medium">云盘空间不足，请扩容或清理文件</p>
                    )}
                  </>
                );
              })()}
            </div>

            {/* Overview cards */}
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <div className="bg-white rounded-lg border border-stone-200 p-4">
                <div className="flex items-center gap-2 mb-2">
                  <HardDrive className="w-4 h-4 text-primary-600" />
                  <span className="text-xs font-medium text-stone-500">总存储用量</span>
                </div>
                <p className="text-2xl font-bold text-stone-900">{formatSize(stats?.totalStorageUsed || 0)}</p>
              </div>
              <div className="bg-white rounded-lg border border-stone-200 p-4">
                <div className="flex items-center gap-2 mb-2">
                  <Users className="w-4 h-4 text-blue-500" />
                  <span className="text-xs font-medium text-stone-500">用户数</span>
                </div>
                <p className="text-2xl font-bold text-stone-900">{users.length}</p>
              </div>
              <div className="bg-white rounded-lg border border-stone-200 p-4">
                <div className="flex items-center gap-2 mb-2">
                  <FileText className="w-4 h-4 text-green-500" />
                  <span className="text-xs font-medium text-stone-500">总文件数</span>
                </div>
                <p className="text-2xl font-bold text-stone-900">{stats?.totalFiles || 0}</p>
              </div>
            </div>

            {/* Per-user storage table */}
            <Card>
              <div className="px-4 py-3 border-b border-stone-100">
                <h3 className="text-sm font-semibold text-stone-700">用户存储配额管理</h3>
              </div>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>用户</TableHead>
                    <TableHead className="w-[280px]">存储用量</TableHead>
                    <TableHead className="w-[140px]">使用率</TableHead>
                    <TableHead className="w-[140px]">配额</TableHead>
                    <TableHead className="text-center w-[80px]">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {users.map((user) => {
                    const used = Number(user.storageUsed || 0);
                    const quota = Number(user.storageQuota || 0);
                    const pct = quota > 0 ? Math.min((used / quota) * 100, 100) : 0;
                    const isUnlimited = quota === 0;
                    const isHigh = pct > 90;
                    return (
                      <TableRow key={user.id}>
                        <TableCell>
                          <div className="flex items-center gap-2">
                            <div className="w-7 h-7 rounded-full bg-stone-200 flex items-center justify-center text-xs font-medium text-stone-600">
                              {(user.nickname || user.username || '?')[0].toUpperCase()}
                            </div>
                            <div>
                              <div className="text-sm font-medium text-stone-800">{user.username}</div>
                              {user.nickname && <div className="text-xs text-stone-400">{user.nickname}</div>}
                            </div>
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="space-y-1">
                            <div className="flex items-center justify-between text-xs">
                              <span className="text-stone-600 font-medium">{formatSize(used)}</span>
                              <span className="text-stone-400">{isUnlimited ? '不限制' : formatSize(quota)}</span>
                            </div>
                            <div className="w-full h-1.5 bg-stone-100 rounded-full overflow-hidden">
                              <div
                                className={cn('h-full rounded-full transition-all', isHigh ? 'bg-red-500' : isUnlimited ? 'bg-stone-300' : 'bg-primary-500')}
                                style={{ width: isUnlimited ? '8%' : pct + '%' }}
                              />
                            </div>
                          </div>
                        </TableCell>
                        <TableCell>
                          {isUnlimited ? (
                            <span className="text-xs text-stone-400">-</span>
                          ) : (
                            <span className={cn('text-xs font-medium', isHigh ? 'text-red-600' : 'text-stone-600')}>
                              {pct.toFixed(1)}%
                            </span>
                          )}
                        </TableCell>
                        <TableCell>
                          <span className="text-xs text-stone-600">{isUnlimited ? '不限制' : formatSize(quota)}</span>
                        </TableCell>
                        <TableCell className="text-center">
                          <button
                            onClick={() => setQuotaTarget(user)}
                            className="text-stone-400 hover:text-primary-600 transition-colors cursor-pointer"
                            title="修改配额"
                          >
                            <Edit3 className="w-4 h-4" />
                          </button>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
              {users.length === 0 && (
                <div className="py-10 text-center text-sm text-stone-400">暂无用户数据</div>
              )}
            </Card>
          </div>
        )}

        {/* Audit Tab */}
        {tab === 'audit' && (
          <div className="space-y-4">
            {/* Filter bar */}
            <Card className="p-4">
              <div className="flex flex-wrap items-center gap-3">
                {/* 操作类型 */}
                <div className="flex items-center gap-2">
                  <span className="text-xs text-stone-500 whitespace-nowrap">操作类型</span>
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
                  <span className="text-xs text-stone-500 whitespace-nowrap">状态</span>
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
                  <span className="text-xs text-stone-500 whitespace-nowrap">排序</span>
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
                  <span className="text-xs text-stone-500 whitespace-nowrap">用户</span>
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
                  <span className="text-xs text-stone-500 whitespace-nowrap">IP</span>
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
                  <span className="text-xs text-stone-500 whitespace-nowrap">目标名称</span>
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
                  <span className="text-xs text-stone-500 whitespace-nowrap">关键词</span>
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
                  <span className="text-xs text-stone-500 whitespace-nowrap">时间</span>
                  <Popover open={auditPopoverOpen} onOpenChange={setAuditPopoverOpen}>
                    <PopoverTrigger asChild>
                      <Button variant="outline" size="sm" className="h-8 w-[340px] justify-start text-left font-normal">
                        <CalendarIcon className="mr-2 h-4 w-4 text-stone-400" />
                        {auditDateRange?.from ? (
                          auditDateRange.to ? (
                            <span className="truncate">
                              {format(auditDateRange.from, 'MM/dd')} {auditStartTime}
                              <span className="mx-1 text-stone-300">→</span>
                              {format(auditDateRange.to, 'MM/dd')} {auditEndTime}
                            </span>
                          ) : (
                            <span className="truncate">
                              {format(auditDateRange.from, 'MM/dd')} {auditStartTime}
                              <span className="mx-1 text-stone-300">→</span>
                              <span className="text-stone-400">待选</span>
                            </span>
                          )
                        ) : (
                          <span className="text-stone-400">选择日期时间范围</span>
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
                <div className="py-10 text-center text-sm text-stone-400">暂无符合条件的审计日志</div>
              )}
              {auditTotal > 20 && (
                <div className="flex items-center justify-between px-4 py-3 border-t border-stone-100">
                  <span className="text-xs text-stone-400">共 {auditTotal} 条</span>
                  <div className="flex gap-2">
                    <Button variant="outline" size="sm" disabled={auditPage === 1} onClick={() => setAuditPage(p => Math.max(1, p - 1))}>上一页</Button>
                    <span className="px-3 py-1 text-xs text-stone-500">{auditPage}</span>
                    <Button variant="outline" size="sm" disabled={auditLogs.length < 20} onClick={() => setAuditPage(p => p + 1)}>下一页</Button>
                  </div>
                </div>
              )}
            </Card>
          </div>
        )}

        {/* 限速管理 */}
        {tab === 'speedLimit' && <SpeedLimitPanel />}

        {/* Quota edit dialog */}
        {quotaTarget && (
          <QuotaEditDialog
            user={quotaTarget}
            onClose={() => setQuotaTarget(null)}
            onSave={(bytes) => handleUpdateQuota(quotaTarget.id, bytes)}
          />
        )}

        {/* Cloud capacity edit dialog */}
        {cloudCapacityOpen && (
          <CloudCapacityEditDialog
            currentCapacity={stats?.cloudTotalCapacity ?? null}
            used={stats?.cloudStorageUsed || 0}
            onClose={() => setCloudCapacityOpen(false)}
            onSave={(bytes) => handleSaveCloudCapacity(bytes)}
          />
        )}
      </div>
    </div>
  );
}
