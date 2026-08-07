import { useState, useRef, useEffect, useCallback } from 'react';
import { cn } from '@/lib/utils';

interface TimeWheelPickerProps {
  value: string; // HH:mm:ss
  onChange: (value: string) => void;
}

const HOURS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'));
const MINUTES = Array.from({ length: 60 }, (_, i) => String(i).padStart(2, '0'));
const SECONDS = Array.from({ length: 60 }, (_, i) => String(i).padStart(2, '0'));

function WheelColumn({
  items,
  value,
  onChange,
  label,
}: {
  items: string[];
  value: string;
  onChange: (v: string) => void;
  label: string;
}) {
  const listRef = useRef<HTMLDivElement>(null);
  const itemHeight = 32;
  const visibleCount = 3;

  const scrollToValue = useCallback((val: string, animate = true) => {
    const idx = items.indexOf(val);
    if (idx < 0 || !listRef.current) return;
    listRef.current.scrollTo({
      top: idx * itemHeight,
      behavior: animate ? 'smooth' : 'auto',
    });
  }, [items]);

  useEffect(() => {
    // slight delay to ensure DOM is ready
    const timer = setTimeout(() => scrollToValue(value, false), 10);
    return () => clearTimeout(timer);
  }, [value, scrollToValue]);

  function handleScroll() {
    if (!listRef.current) return;
    const scrollTop = listRef.current.scrollTop;
    const idx = Math.round(scrollTop / itemHeight);
    const clamped = Math.max(0, Math.min(items.length - 1, idx));
    const newVal = items[clamped];
    if (newVal !== value) {
      onChange(newVal);
    }
  }

  // snap on scroll end
  let scrollTimer: ReturnType<typeof setTimeout>;
  function onScroll() {
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(handleScroll, 80);
  }

  return (
    <div className="flex flex-col items-center">
      <div className="text-[10px] text-stone-400 mb-1 uppercase tracking-wider">{label}</div>
      <div className="relative" style={{ height: itemHeight * visibleCount, width: 48 }}>
        {/* highlight strip */}
        <div
          className="absolute left-0 right-0 w-full bg-primary-50 rounded-lg pointer-events-none border border-primary-100"
          style={{
            top: itemHeight * 1,
            height: itemHeight,
          }}
        />
        {/* gradient fade */}
        <div className="absolute top-0 left-0 right-0 h-[32px] bg-gradient-to-b from-white to-transparent pointer-events-none z-10" />
        <div className="absolute bottom-0 left-0 right-0 h-[32px] bg-gradient-to-t from-white to-transparent pointer-events-none z-10" />
        {/* scrollable list */}
        <div
          ref={listRef}
          onScroll={onScroll}
          className="h-full overflow-y-auto scrollbar-hide snap-y snap-mandatory"
          style={{
            scrollbarWidth: 'none',
            msOverflowStyle: 'none',
            paddingTop: itemHeight * 1,
            paddingBottom: itemHeight * 1,
          }}
        >
          {items.map((item) => (
            <div
              key={item}
              onClick={() => {
                onChange(item);
                scrollToValue(item, true);
              }}
              className={cn(
                'flex items-center justify-center cursor-pointer transition-colors snap-center',
              )}
              style={{ height: itemHeight }}
            >
              <span
                className={cn(
                  'text-sm transition',
                  item === value
                    ? 'text-primary-600 font-semibold scale-110'
                    : 'text-stone-400 hover:text-stone-600'
                )}
              >
                {item}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

export default function TimeWheelPicker({ value, onChange }: TimeWheelPickerProps) {
  const [parts, setParts] = useState(value.split(':'));

  useEffect(() => {
    setParts(value.split(':'));
  }, [value]);

  function updatePart(idx: number, v: string) {
    const next = [...parts];
    next[idx] = v;
    const newVal = next.join(':');
    onChange(newVal);
  }

  return (
    <div className="flex items-start justify-center gap-1 py-1">
      <WheelColumn items={HOURS} value={parts[0]} onChange={(v) => updatePart(0, v)} label="时" />
      <div className="flex items-center pt-6">
        <span className="text-stone-300 text-sm font-medium">:</span>
      </div>
      <WheelColumn items={MINUTES} value={parts[1]} onChange={(v) => updatePart(1, v)} label="分" />
      <div className="flex items-center pt-6">
        <span className="text-stone-300 text-sm font-medium">:</span>
      </div>
      <WheelColumn items={SECONDS} value={parts[2]} onChange={(v) => updatePart(2, v)} label="秒" />
    </div>
  );
}
