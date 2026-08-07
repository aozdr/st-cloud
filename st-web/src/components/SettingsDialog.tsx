import { X, Check } from 'lucide-react';
import { THEMES } from '../themes';
import { useThemeStore } from '../store/theme';

interface SettingsDialogProps {
  open: boolean;
  onClose: () => void;
}

export default function SettingsDialog({ open, onClose }: SettingsDialogProps) {
  const themeKey = useThemeStore((s) => s.themeKey);
  const setTheme = useThemeStore((s) => s.setTheme);

  if (!open) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-content w-[460px] max-w-[92vw] p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-5">
          <h2 className="text-lg font-semibold text-stone-900">设置</h2>
          <button
            onClick={onClose}
            className="p-1 text-stone-400 hover:text-stone-600 rounded-md hover:bg-stone-100 transition-colors cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <label className="block text-sm font-medium text-stone-700 mb-3">主题色</label>
        <div className="grid grid-cols-3 gap-3">
          {THEMES.map((theme) => {
            const selected = theme.key === themeKey;
            return (
              <button
                key={theme.key}
                onClick={() => setTheme(theme.key)}
                className={`relative flex flex-col items-center gap-2 p-3 rounded-lg border-2 transition duration-150 cursor-pointer ${
                  selected ? 'border-stone-800 bg-stone-50' : 'border-stone-200 hover:border-stone-300 hover:bg-stone-50'
                }`}
              >
                <div className="w-10 h-10 rounded-full flex items-center justify-center" style={{ backgroundColor: theme.preview }}>
                  {selected && <Check className="w-5 h-5 text-white" />}
                </div>
                <span className={`text-xs font-medium ${selected ? 'text-stone-900' : 'text-stone-600'}`}>{theme.label}</span>
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