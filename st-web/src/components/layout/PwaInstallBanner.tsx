import { usePwaInstall } from '../../hooks/usePwaInstall';
import { Download, X } from 'lucide-react';

/**
 * PWA 安装引导横幅
 * 首次访问且浏览器支持安装时显示,用户关闭或安装后不再提示
 */
export default function PwaInstallBanner() {
  const { canInstall, promptInstall, dismiss } = usePwaInstall();

  if (!canInstall) return null;

  return (
    <div className="fixed bottom-20 md:bottom-4 inset-x-3 z-40 mx-auto max-w-sm bg-surface rounded-xl shadow-float border border-border p-3 flex items-center gap-3 animate-dialog-pop">
      <div className="w-9 h-9 rounded-lg bg-primary-600/15 flex items-center justify-center flex-shrink-0">
        <Download className="w-[18px] h-[18px] text-primary-600" aria-hidden />
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-sm font-medium text-fg">安装星云盘</p>
        <p className="text-xs text-muted">添加到主屏幕，获得更好体验</p>
      </div>
      <button
        onClick={promptInstall}
        className="px-3 py-1.5 text-xs font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700 transition-colors cursor-pointer flex-shrink-0 min-h-[36px]"
      >
        安装
      </button>
      <button
        onClick={dismiss}
        className="p-1.5 text-muted hover:text-fg rounded-lg cursor-pointer flex-shrink-0"
        aria-label="关闭"
      >
        <X className="w-4 h-4" aria-hidden />
      </button>
    </div>
  );
}