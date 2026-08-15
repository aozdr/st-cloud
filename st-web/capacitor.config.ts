import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Capacitor 配置
 * webDir 指向 st-web 构建产物,Capacitor 将其同步到 android 工程
 * 企业内部分发,不上架商店
 */
const config: CapacitorConfig = {
  appId: 'com.stcloud.app',
  appName: '星云盘',
  webDir: 'dist',
  // 安全区适配(刘海屏/手势条)
  server: {
    androidScheme: 'https',
  },
  android: {
    // 禁止混合内容,强制 HTTPS
    allowMixedContent: false,
    // WebView 背景与主题色一致
    backgroundColor: '#0f0f11',
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 1000,
      backgroundColor: '#0f0f11',
      showSpinner: false,
    },
    StatusBar: {
      style: 'DARK',
      backgroundColor: '#0f0f11',
    },
  },
};

export default config;