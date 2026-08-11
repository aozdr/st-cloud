import { useEffect, useRef, type CSSProperties } from 'react';
import Plyr from 'plyr';
import 'plyr/dist/plyr.css';

export default function PlyrPlayer({ src }: { src: string }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const playerRef = useRef<Plyr | null>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const video = document.createElement('video');
    video.src = src;
    video.controls = true;
    video.playsInline = true;
    video.crossOrigin = 'anonymous';
    video.style.maxWidth = '100%';
    video.style.maxHeight = '80vh';
    // 尝试加载同名字幕文件（.vtt/.srt）
    const baseName = src.replace(/\.[^/.]+$/, '');
    const track = document.createElement('track');
    track.kind = 'subtitles';
    track.label = '字幕';
    track.srclang = 'zh';
    track.src = baseName + '.vtt';
    track.default = true;
    video.appendChild(track);
    container.appendChild(video);

    playerRef.current = new Plyr(video, {
      autoplay: true,
      controls: ['play-large', 'play', 'progress', 'current-time', 'duration', 'mute', 'volume', 'settings', 'pip', 'airplay', 'fullscreen'],
      settings: ['speed', 'captions'],
      speed: { selected: 1, options: [0.5, 0.75, 1, 1.25, 1.5, 2] },
      keyboard: { focused: true, global: true },
      tooltips: { controls: true, seek: true },
      seekTime: 10,
    });

    return () => {
      playerRef.current?.destroy();
      playerRef.current = null;
      container.innerHTML = '';
    };
  }, [src]);

  return (
    <div
      ref={containerRef}
      style={{ width: '80vw', maxWidth: '1280px', '--plyr-color-main': 'rgb(var(--color-primary-600))', '--plyr-video-background': '#000' } as CSSProperties}
      className="rounded-lg bg-black"
    />
  );
}