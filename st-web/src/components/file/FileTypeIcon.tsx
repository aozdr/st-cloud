import type { FileTypeConfig } from '../../lib/utils';

interface Props {
  config: FileTypeConfig;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  isFolder?: boolean;
  suffix?: string | null;
  className?: string;
}

const SIZE_MAP = {
  sm: 18,
  md: 24,
  lg: 28,
  xl: 48,
};

const FOLDER_COLORS = {
  body: '#FFDB70',
  bodyDark: '#E0BC52',
  flap: '#FFEBA0',
  flapDark: '#E0BC52',
};

const TYPE_BADGE: Record<string, { bg: string; fg: string; text: string }> = {
  '\u56fe\u7247': { bg: '#22C55E', fg: '#FFFFFF', text: 'IMG' },
  '\u89c6\u9891': { bg: '#A855F7', fg: '#FFFFFF', text: 'VID' },
  '\u97f3\u9891': { bg: '#EC4899', fg: '#FFFFFF', text: 'AUD' },
  'PDF': { bg: '#EF4444', fg: '#FFFFFF', text: 'PDF' },
  'Word': { bg: '#2563EB', fg: '#FFFFFF', text: 'DOC' },
  'Excel': { bg: '#059669', fg: '#FFFFFF', text: 'XLS' },
  'PPT': { bg: '#F97316', fg: '#FFFFFF', text: 'PPT' },
  '\u538b\u7f29\u5305': { bg: '#F59E0B', fg: '#FFFFFF', text: 'ZIP' },
  '\u6587\u6863': { bg: '#78716C', fg: '#FFFFFF', text: 'TXT' },
};

function FolderSVG({ size }: { size: number }) {
  const w = size;
  const h = size;
  return (
    <svg width={w} height={h} viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg">
      {/* Back panel with tab (open folder back) */}
      <path d="M9 9C7.9 9 7 9.9 7 11V38C7 39.1 7.9 40 9 40H39C40.1 40 41 39.1 41 38V14C41 12.9 40.1 12 39 12H22L19 9.4C18.6 9.1 18.2 9 17.8 9H9Z" fill={FOLDER_COLORS.bodyDark} />
      {/* Front body (pale yellow, open folder front cover) */}
      <rect x="7" y="15" width="34" height="25" rx="2" fill={FOLDER_COLORS.body} />
      {/* Top edge highlight (folder opening lip) */}
      <rect x="7" y="15" width="34" height="3" rx="1.5" fill={FOLDER_COLORS.flap} />
    </svg>
  );
}

function FileSVG({ size, label }: { size: number; label: string }) {
  const w = size;
  const h = size;
  const badge = TYPE_BADGE[label];

  return (
    <svg width={w} height={h} viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg">
      {/* File body shadow */}
      <path d="M12 4H30L40 14V42C40 43.1 39.1 44 38 44H12C10.9 44 10 43.1 10 42V6C10 4.9 10.9 4 12 4Z" fill="#E7E5E4" />
      {/* File body */}
      <path d="M12 4H28L38 14V42C38 43.1 37.1 44 36 44H12C10.9 44 10 43.1 10 42V6C10 4.9 10.9 4 12 4Z" fill="#FFFFFF" stroke="#D6D3D1" strokeWidth="0.5" />
      {/* Folded corner */}
      <path d="M28 4L38 14H30C28.9 14 28 13.1 28 12V4Z" fill="#D6D3D1" />
      <path d="M28 4L38 14H30C28.9 14 28 13.1 28 12V4Z" fill="none" stroke="#D6D3D1" strokeWidth="0.5" />

      {/* Type badge strip at bottom */}
      {badge && (
        <>
          <rect x="10" y="32" width="28" height="12" rx="2" fill={badge.bg} />
          <text
            x="24" y="40.5"
            textAnchor="middle"
            fontSize="7"
            fontWeight="700"
            fontFamily="Arial, sans-serif"
            fill={badge.fg}
            letterSpacing="0.5"
          >
            {badge.text}
          </text>
        </>
      )}

      {/* For images, show a tiny mountain+sun icon */}
      {label === '\u56fe\u7247' && !badge && (
        <g opacity="0.6">
          <circle cx="18" cy="22" r="3" fill="#22C55E" />
          <path d="M10 30L20 22L26 28L30 24L38 30V32H10V30Z" fill="#22C55E" />
        </g>
      )}
    </svg>
  );
}

export default function FileTypeIcon({ config, size = 'md', isFolder = false }: Props) {
  const iconSize = SIZE_MAP[size];

  if (isFolder) {
    return <FolderSVG size={iconSize} />;
  }

  return <FileSVG size={iconSize} label={config.label} />;
}
