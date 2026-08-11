import { useState, useEffect } from 'react';

const DISMISS_KEY = 'pwaInstallDismissed';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

/**
 * PWA 安装引导 hook
 * 监听 beforeinstallprompt 事件,捕获后可触发安装提示
 * 用户已关闭或已安装则不再提示(localStorage 记忆)
 */
export function usePwaInstall() {
  const [installPrompt, setInstallPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  const [canInstall, setCanInstall] = useState(false);

  useEffect(() => {
    // 已安装或已关闭不再提示
    if (localStorage.getItem(DISMISS_KEY) === '1') return;
    // 已 standalone(已安装) 不提示
    if (window.matchMedia('(display-mode: standalone)').matches) return;

    const handler = (e: Event) => {
      e.preventDefault();
      setInstallPrompt(e as BeforeInstallPromptEvent);
      setCanInstall(true);
    };
    window.addEventListener('beforeinstallprompt', handler);
    return () => window.removeEventListener('beforeinstallprompt', handler);
  }, []);

  const promptInstall = async () => {
    if (!installPrompt) return;
    await installPrompt.prompt();
    const choice = await installPrompt.userChoice;
    if (choice.outcome === 'dismissed') {
      localStorage.setItem(DISMISS_KEY, '1');
    }
    setInstallPrompt(null);
    setCanInstall(false);
  };

  const dismiss = () => {
    localStorage.setItem(DISMISS_KEY, '1');
    setCanInstall(false);
    setInstallPrompt(null);
  };

  return { canInstall, promptInstall, dismiss };
}