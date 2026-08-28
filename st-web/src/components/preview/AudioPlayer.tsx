import { memo, useEffect, useRef, useState, useCallback } from 'react';
import { Play, Pause, SkipBack, SkipForward, Repeat, Repeat1, Shuffle, Volume2, VolumeX } from 'lucide-react';
import { cn, formatSize } from '../../lib/utils';
import type { FileNode } from '../../types';

export type LoopMode = 'off' | 'all' | 'one' | 'shuffle';

interface Props {
  file: FileNode;
  src: string;
  /** 当前播放队列（同一预览列表中的全部音频） */
  queue: FileNode[];
  /** 当前曲目在队列中的下标 */
  trackIndex: number;
  /** 切换曲目（由父级驱动预览切换） */
  onSwitchTrack: (queueIndex: number) => void;
}

function formatTime(sec: number): string {
  if (!Number.isFinite(sec)) return '0:00';
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${String(s).padStart(2, '0')}`;
}

function AudioPlayerInner({ file, src, queue, trackIndex, onSwitchTrack }: Props) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const [playing, setPlaying] = useState(false);
  const [current, setCurrent] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(0.8);
  const [muted, setMuted] = useState(false);
  const [loopMode, setLoopMode] = useState<LoopMode>('all');
  const loopRef = useRef(loopMode);

  useEffect(() => { loopRef.current = loopMode; }, [loopMode]);
  useEffect(() => { setCurrent(0); setDuration(0); }, [src]);
  useEffect(() => {
    if (audioRef.current) audioRef.current.volume = volume;
  }, [volume]);
  useEffect(() => {
    if (audioRef.current) audioRef.current.muted = muted;
  }, [muted]);

  // 切歌后自动播放
  useEffect(() => {
    const el = audioRef.current;
    if (!el) return;
    el.currentTime = 0;
    el.play().catch(() => setPlaying(false));
  }, [src]);

  const togglePlay = useCallback(() => {
    const el = audioRef.current;
    if (!el) return;
    if (el.paused) el.play().catch(() => {});
    else el.pause();
  }, []);

  const handleSeek = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    const el = audioRef.current;
    if (!el || !Number.isFinite(el.duration)) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
    el.currentTime = ratio * el.duration;
    setCurrent(el.currentTime);
  }, []);

  const goTrack = useCallback((delta: 1 | -1) => {
    if (queue.length === 0) return;
    const mode = loopRef.current;
    let next: number;
    if (mode === 'shuffle' && queue.length > 1) {
      next = trackIndex;
      while (next === trackIndex) next = Math.floor(Math.random() * queue.length);
    } else {
      next = (trackIndex + delta + queue.length) % queue.length;
    }
    onSwitchTrack(next);
  }, [queue.length, trackIndex, onSwitchTrack]);

  const handleEnded = useCallback(() => {
    const mode = loopRef.current;
    if (mode === 'one') {
      const el = audioRef.current;
      if (el) { el.currentTime = 0; el.play().catch(() => {}); }
      return;
    }
    // 列表一遍且当前是最后一首 → 停止
    if (mode === 'off' && trackIndex >= queue.length - 1) { setPlaying(false); return; }
    goTrack(1);
  }, [trackIndex, queue.length, goTrack]);

  const progress = duration > 0 ? (current / duration) * 100 : 0;

  return (
    <div className="w-[420px] max-w-[90vw] bg-surface rounded-2xl border border-border overflow-hidden shadow-card">
      {/* 播放信息头 */}
      <div className="px-5 pt-5 pb-3 text-center">
        <p className="text-sm font-medium text-fg truncate" title={file.name}>{file.name}</p>
        <p className="text-[11px] text-muted mt-0.5">{formatSize(file.fileSize)} · {queue.length > 1 ? `队列 ${trackIndex + 1}/${queue.length}` : '单曲'}</p>
      </div>
      {/* 进度条 */}
      <div className="px-5">
        <div
          className="h-1.5 bg-surface-2 rounded-full cursor-pointer group relative"
          onClick={handleSeek}
          role="slider"
          aria-label="播放进度"
          aria-valuenow={Math.round(progress)}
          aria-valuemin={0}
          aria-valuemax={100}
        >
          <div className="h-full bg-primary-500 rounded-full relative transition-[width] duration-150" style={{ width: `${progress}%` }}>
            <span className="absolute right-0 top-1/2 -translate-y-1/2 translate-x-1/2 w-3 h-3 rounded-full bg-primary-500 opacity-0 group-hover:opacity-100 transition-opacity shadow" />
          </div>
        </div>
        <div className="flex justify-between text-[11px] text-muted tabular-nums mt-1.5">
          <span>{formatTime(current)}</span>
          <span>{formatTime(duration)}</span>
        </div>
      </div>
      {/* 控制条 */}
      <div className="flex items-center justify-center gap-2 px-5 py-3">
        <button
          onClick={() => setLoopMode((m) => (m === 'all' ? 'one' : m === 'one' ? 'shuffle' : m === 'shuffle' ? 'off' : 'all'))}
          className={cn(
            'w-8 h-8 flex items-center justify-center rounded-full cursor-pointer transition-colors',
            loopMode === 'off' ? 'text-muted hover:text-fg' : 'text-primary-500 bg-primary-500/10',
          )}
          title={loopMode === 'off' ? '列表一遍' : loopMode === 'all' ? '列表循环' : loopMode === 'one' ? '单曲循环' : '随机播放'}
          aria-label={`循环模式：${loopMode}`}
        >
          {loopMode === 'shuffle' ? <Shuffle className="w-4 h-4" /> : loopMode === 'one' ? <Repeat1 className="w-4 h-4" /> : <Repeat className="w-4 h-4" />}
        </button>
        <button
          onClick={() => goTrack(-1)}
          disabled={queue.length <= 1}
          className="w-9 h-9 flex items-center justify-center text-fg hover:bg-surface-2 rounded-full cursor-pointer transition-colors disabled:opacity-30 disabled:cursor-default"
          aria-label="上一首"
        >
          <SkipBack className="w-4.5 h-4.5" />
        </button>
        <button
          onClick={togglePlay}
          className="w-12 h-12 flex items-center justify-center bg-primary-600 hover:bg-primary-700 text-white rounded-full cursor-pointer transition-colors shadow-primary"
          aria-label={playing ? '暂停' : '播放'}
        >
          {playing ? <Pause className="w-5 h-5" /> : <Play className="w-5 h-5 ml-0.5" />}
        </button>
        <button
          onClick={() => goTrack(1)}
          disabled={queue.length <= 1}
          className="w-9 h-9 flex items-center justify-center text-fg hover:bg-surface-2 rounded-full cursor-pointer transition-colors disabled:opacity-30 disabled:cursor-default"
          aria-label="下一首"
        >
          <SkipForward className="w-4.5 h-4.5" />
        </button>
        {/* 音量 */}
        <div className="flex items-center gap-1.5 ml-1">
          <button
            onClick={() => setMuted((m) => !m)}
            className="w-7 h-7 flex items-center justify-center text-muted hover:text-fg rounded-full cursor-pointer transition-colors"
            aria-label={muted ? '取消静音' : '静音'}
          >
            {muted ? <VolumeX className="w-4 h-4" /> : <Volume2 className="w-4 h-4" />}
          </button>
          <input
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={muted ? 0 : volume}
            onChange={(e) => { setVolume(Number(e.target.value)); setMuted(false); }}
            className="w-16 h-1 accent-primary-600 cursor-pointer"
            aria-label="音量"
          />
        </div>
      </div>
      {/* 播放列表面板 */}
      {queue.length > 1 && (
        <div className="border-t border-border max-h-40 overflow-y-auto">
          {queue.map((track, idx) => (
            <button
              key={track.id}
              onClick={() => onSwitchTrack(idx)}
              className={cn(
                'w-full flex items-center gap-2.5 px-5 py-2 text-left text-xs cursor-pointer transition-colors hover:bg-surface-2',
                idx === trackIndex ? 'text-primary-600 font-medium bg-primary-500/5' : 'text-fg',
              )}
            >
              <span className="w-5 text-right tabular-nums text-muted flex-shrink-0">{idx + 1}</span>
              <span className="min-w-0 flex-1 truncate" title={track.name}>{track.name}</span>
              {idx === trackIndex && playing && <span className="w-1.5 h-1.5 rounded-full bg-primary-500 animate-pulse flex-shrink-0" />}
            </button>
          ))}
        </div>
      )}
      {/* 隐藏的原生 audio 元素：实际播放引擎 */}
      <audio
        ref={audioRef}
        src={src}
        autoPlay
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onTimeUpdate={(e) => setCurrent(e.currentTarget.currentTime)}
        onLoadedMetadata={(e) => setDuration(e.currentTarget.duration)}
        onEnded={handleEnded}
        className="hidden"
      />
    </div>
  );
}

export default memo(AudioPlayerInner);
