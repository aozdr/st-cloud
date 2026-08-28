import { useState } from 'react';
import { Calendar as CalendarIcon } from 'lucide-react';
import { format } from 'date-fns';
import { Button } from '../../ui/button';
import { Calendar } from '../../ui/calendar';
import TimeWheelPicker from '../../ui/time-wheel-picker';
import type { AuditDateRange } from './ExpandedAuditDetail';
export default function DateTimeRangePanel({
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


