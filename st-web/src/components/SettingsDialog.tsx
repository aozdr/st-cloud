import { X, Check, Sun, Moon, Monitor } from 'lucide-react';
import { THEMES } from '../themes';
import { useThemeStore, type ThemeMode } from '../store/theme';

interface SettingsDialogProps {
  open: boolean;
  onClose: () => void;
}

const MODES: { key: ThemeMode; label: string; icon: typeof Sun }[] = [
  { key: 'light', label: '浅色', icon: Sun },
  { key: 'dark', label: '深色', icon: Moon },
  { key: 'system', label: '跟随系统', icon: Monitor },
];

export default function SettingsDialog({ open, onClose }: SettingsDialogProps) {
  const themeKey = useThemeStore((s) => s.themeKey);
  const setTheme = useThemeStore((s) => s.setTheme);
  const mode = useThemeStore((s) => s.mode);
  const setMode = useThemeStore((s) => s.setMode);

  if (!open) return null;

  return (
    <div className="modal-overlay" onClick={onClose} role="presentation">
      <div
        role="dialog"
        aria-modal="true"
        aria-label="设置"
        tabIndex={-1}
        className="modal-content w-[460px] max-w-[92vw] p-6 focus:outline-none"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-5">
          <h2 className="text-lg font-semibold text-fg">设置</h2>
          <button
            onClick={onClose}
            aria-label="关闭设置"
            className="p-1 text-muted hover:text-fg rounded-md hover:bg-surface-2 transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <X className="w-5 h-5" aria-hidden />
          </button>
        </div>

        {/* Appearance mode */}
        <label className="block text-sm font-medium text-fg mb-3">外观模式</label>
        <div className="grid grid-cols-3 gap-3 mb-6">
          {MODES.map((m) => {
            const selected = m.key === mode;
            const Icon = m.icon;
            return (
              <button
                key={m.key}
                onClick={() => setMode(m.key)}
                aria-pressed={selected}
                className={`relative flex flex-col items-center gap-2 p-3 rounded-lg border-2 transition-colors duration-150 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                  selected ? 'border-primary-600 bg-primary-500/10' : 'border-border hover:bg-surface-2'
                }`}
              >
                <Icon className={`w-5 h-5 ${selected ? 'text-primary-600' : 'text-muted'}`} aria-hidden />
                <span className={`text-xs font-medium ${selected ? 'text-primary-600' : 'text-muted'}`}>{m.label}</span>
              </button>
            );
          })}
        </div>

        {/* Accent color */}
        <label className="block text-sm font-medium text-fg mb-3">主题色</label>
        <div className="grid grid-cols-3 gap-3">
          {THEMES.map((theme) => {
            const selected = theme.key === themeKey;
            return (
              <button
                key={theme.key}
                onClick={() => setTheme(theme.key)}
                aria-pressed={selected}
                aria-label={`主题色 ${theme.label}`}
                className={`relative flex flex-col items-center gap-2 p-3 rounded-lg border-2 transition-colors duration-150 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                  selected ? 'border-fg bg-surface-2' : 'border-border hover:bg-surface-2'
                }`}
              >
                <div className="w-10 h-10 rounded-full flex items-center justify-center" style={{ backgroundColor: theme.preview }}>
                  {selected && <Check className="w-5 h-5 text-white" aria-hidden />}
                </div>
                <span className={`text-xs font-medium ${selected ? 'text-fg' : 'text-muted'}`}>{theme.label}</span>
              </button>
            );
          })}
        </div>

        <div className="mt-6 flex justify-end">
          <button onClick={onClose} className="btn-primary">完成</button>
        </div>
      </div>
    </div>
  );
}
