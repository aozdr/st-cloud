import { useState } from 'react';
import { X, Settings2, ArrowUp, ArrowDown, Layers } from 'lucide-react';
import { useTransferStore } from '../store/transfer';

interface TransferSettingsDialogProps {
  open: boolean;
  onClose: () => void;
}

const PARALLEL_OPTIONS = [1, 2, 3, 4, 5];

export default function TransferSettingsDialog({ open, onClose }: TransferSettingsDialogProps) {
  const settings = useTransferStore((s) => s.settings);
  const setSettings = useTransferStore((s) => s.setSettings);

  // 本地编辑态：value(数字) + unit(kb/mb)
  const [uploadValue, setUploadValue] = useState(() => kbToDisplay(settings.uploadSpeedLimit));
  const [downloadValue, setDownloadValue] = useState(() => kbToDisplay(settings.downloadSpeedLimit));

  if (!open) return null;

  const applyUpload = (unlimited: boolean, val: number, unit: 'kb' | 'mb') => {
    if (unlimited) {
      setSettings({ uploadSpeedLimit: 0 });
      return;
    }
    const kbps = unit === 'mb' ? val * 1024 : val;
    setSettings({ uploadSpeedLimit: kbps > 0 ? kbps : 0 });
  };

  const applyDownload = (unlimited: boolean, val: number, unit: 'kb' | 'mb') => {
    if (unlimited) {
      setSettings({ downloadSpeedLimit: 0 });
      return;
    }
    const kbps = unit === 'mb' ? val * 1024 : val;
    setSettings({ downloadSpeedLimit: kbps > 0 ? kbps : 0 });
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-content w-[460px] max-w-[90vw] p-6"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between mb-5">
          <div className="flex items-center gap-2">
            <Settings2 className="w-5 h-5 text-stone-700" />
            <h2 className="text-lg font-semibold text-stone-900">传输设置</h2>
          </div>
          <button
            onClick={onClose}
            className="p-1 text-stone-400 hover:text-stone-600 rounded-md hover:bg-stone-100 transition-colors cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* 最大并行任务数 */}
        <div className="mb-6">
          <div className="flex items-center gap-2 mb-3">
            <Layers className="w-4 h-4 text-stone-500" />
            <label className="text-sm font-medium text-stone-700">最大并行任务数</label>
            <span className="text-xs text-stone-400">上传/下载共享</span>
          </div>
          <div className="flex gap-2">
            {PARALLEL_OPTIONS.map((n) => (
              <button
                key={n}
                onClick={() => setSettings({ maxParallelTasks: n })}
                className={`flex-1 py-2.5 rounded-lg text-sm font-semibold transition duration-150 cursor-pointer ${
                  settings.maxParallelTasks === n
                    ? 'bg-primary-600 text-white shadow-sm'
                    : 'bg-stone-100 text-stone-600 hover:bg-stone-200'
                }`}
              >
                {n}
              </button>
            ))}
          </div>
        </div>

        <div className="border-t border-stone-100" />

        {/* 上传限速 */}
        <SpeedSection
          icon={<ArrowUp className="w-4 h-4 text-blue-500" />}
          title="上传限速"
          unlimited={settings.uploadSpeedLimit === 0}
          value={uploadValue.value}
          unit={uploadValue.unit}
          onToggleUnlimited={(u) => {
            setUploadValue((s) => ({ ...s }));
            applyUpload(u, uploadValue.value, uploadValue.unit);
          }}
          onValueChange={(val) => {
            setUploadValue((s) => ({ ...s, value: val }));
            applyUpload(false, val, uploadValue.unit);
          }}
          onUnitChange={(unit) => {
            setUploadValue((s) => ({ ...s, unit }));
            applyUpload(false, uploadValue.value, unit);
          }}
        />

        <div className="border-t border-stone-100" />

        {/* 下载限速 */}
        <SpeedSection
          icon={<ArrowDown className="w-4 h-4 text-emerald-500" />}
          title="下载限速"
          unlimited={settings.downloadSpeedLimit === 0}
          value={downloadValue.value}
          unit={downloadValue.unit}
          onToggleUnlimited={(u) => {
            setDownloadValue((s) => ({ ...s }));
            applyDownload(u, downloadValue.value, downloadValue.unit);
          }}
          onValueChange={(val) => {
            setDownloadValue((s) => ({ ...s, value: val }));
            applyDownload(false, val, downloadValue.unit);
          }}
          onUnitChange={(unit) => {
            setDownloadValue((s) => ({ ...s, unit }));
            applyDownload(false, downloadValue.value, unit);
          }}
        />

        <div className="mt-6 flex justify-end">
          <button onClick={onClose} className="btn-primary">
            完成
          </button>
        </div>
      </div>
    </div>
  );
}

// ==================== 限速区块子组件 ====================

interface SpeedSectionProps {
  icon: React.ReactNode;
  title: string;
  unlimited: boolean;
  value: number;
  unit: 'kb' | 'mb';
  onToggleUnlimited: (unlimited: boolean) => void;
  onValueChange: (val: number) => void;
  onUnitChange: (unit: 'kb' | 'mb') => void;
}

function SpeedSection({ icon, title, unlimited, value, unit, onToggleUnlimited, onValueChange, onUnitChange }: SpeedSectionProps) {
  return (
    <div className="py-5">
      <div className="flex items-center gap-2 mb-3">
        {icon}
        <label className="text-sm font-medium text-stone-700">{title}</label>
      </div>

      {/* 不限速开关 */}
      <label className="flex items-center gap-2 mb-3 cursor-pointer">
        <button
          onClick={() => onToggleUnlimited(!unlimited)}
          className={`relative w-9 h-5 rounded-full transition-colors duration-200 cursor-pointer ${
            unlimited ? 'bg-stone-300' : 'bg-primary-600'
          }`}
        >
          <span
            className={`absolute top-0.5 w-4 h-4 bg-white rounded-full shadow-sm transition-transform duration-200 ${
              unlimited ? 'left-0.5' : 'left-[18px]'
            }`}
          />
        </button>
        <span className={`text-sm ${unlimited ? 'text-stone-400' : 'text-stone-700'}`}>
          {unlimited ? '不限速' : '自定义限速'}
        </span>
      </label>

      {/* 自定义输入 */}
      {!unlimited && (
        <div className="flex items-center gap-2">
          <input
            type="number"
            min={1}
            value={value || ''}
            onChange={(e) => {
              const v = parseInt(e.target.value, 10);
              onValueChange(isNaN(v) || v < 1 ? 1 : v);
            }}
            className="flex-1 px-3 py-2 border border-stone-200 rounded-lg text-sm text-stone-900 focus:outline-none focus:border-primary-400 focus:ring-2 focus:ring-primary-50 transition"
            placeholder="输入数值"
          />
          <div className="flex rounded-lg overflow-hidden border border-stone-200">
            <button
              onClick={() => onUnitChange('kb')}
              className={`px-3 py-2 text-sm font-medium transition-colors cursor-pointer ${
                unit === 'kb' ? 'bg-primary-600 text-white' : 'bg-white text-stone-500 hover:bg-stone-50'
              }`}
            >
              KB/s
            </button>
            <button
              onClick={() => onUnitChange('mb')}
              className={`px-3 py-2 text-sm font-medium transition-colors cursor-pointer ${
                unit === 'mb' ? 'bg-primary-600 text-white' : 'bg-white text-stone-500 hover:bg-stone-50'
              }`}
            >
              MB/s
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ==================== 工具函数 ====================

/** 将存储的 KB/s 值转为显示用的 {value, unit} */
function kbToDisplay(kbps: number): { value: number; unit: 'kb' | 'mb' } {
  if (kbps === 0) return { value: 1, unit: 'mb' };
  if (kbps >= 1024 && kbps % 1024 === 0) {
    return { value: kbps / 1024, unit: 'mb' };
  }
  return { value: kbps, unit: 'kb' };
}
