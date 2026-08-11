/**
 * Capacitor 原生壳环境检测与桥接封装
 * 与 electron.ts 并列,提供 Capacitor 运行时能力
 * 降级链: capacitor > electron > web,本模块仅处理 capacitor 分支
 */
import { isCapacitor } from './runtime';

/**
 * 是否在 Capacitor 原生壳内运行
 * 向后兼容: 同时作为 runtime.ts 的再导出
 */
export function isCapacitorEnv(): boolean {
  return isCapacitor();
}

/**
 * Capacitor 原生桥(懒加载,避免 web 环境引入原生插件包)
 * 仅在 capacitor 环境下动态 import 插件,web 环境返回 null
 */
export async function getCapacitorFilesystem() {
  if (!isCapacitor()) return null;
  try {
    const mod = await import('@capacitor/filesystem');
    return mod.Filesystem;
  } catch {
    return null;
  }
}

export async function getCapacitorShare() {
  if (!isCapacitor()) return null;
  try {
    const mod = await import('@capacitor/share');
    return mod.Share;
  } catch {
    return null;
  }
}

export async function getCapacitorCamera() {
  if (!isCapacitor()) return null;
  try {
    const mod = await import('@capacitor/camera');
    return mod.Camera;
  } catch {
    return null;
  }
}

export async function getCapacitorStatusBar() {
  if (!isCapacitor()) return null;
  try {
    const mod = await import('@capacitor/status-bar');
    return mod.StatusBar;
  } catch {
    return null;
  }
}
/**
 * 从相册选择图片(Capacitor 环境)
 * 权限拒绝时抛出带引导文案的错误,供 UI 提示用户去系统设置开启
 * @returns 图片数组(File 对象),非 Capacitor 环境返回 null(降级用 input)
 */
export async function pickFromGallery(): Promise<File[] | null> {
  if (!isCapacitor()) return null;
  try {
    const { Camera, CameraResultType, CameraSource } = await import('@capacitor/camera');
    // 请求相册权限(安卓 13+ 用 photoLibrary,旧版用 photos)
    const perm = await Camera.checkPermissions();
    if (perm.photos === 'prompt' || perm.camera === 'prompt') {
      const req = await Camera.requestPermissions();
      if (req.photos === 'denied' || req.camera === 'denied') {
        throw new Error('需要相册权限才能上传照片，请到「设置 > 应用 > 星云盘 > 权限」开启相册权限');
      }
    } else if (perm.photos === 'denied' || perm.camera === 'denied') {
      throw new Error('需要相册权限才能上传照片，请到「设置 > 应用 > 星云盘 > 权限」开启相册权限');
    }
    // 从相册选图(返回 dataUrl)
    const result = await Camera.getPhoto({
      quality: 80,
      allowEditing: false,
      resultType: CameraResultType.DataUrl,
      source: CameraSource.Photos,
    });
    // dataUrl 转 File
    const res = await fetch(result.dataUrl!);
    const blob = await res.blob();
    const name = result.path ? result.path.split('/').pop() || 'photo.jpg' : 'photo.jpg';
    return [new File([blob], name, { type: blob.type || 'image/jpeg' })];
  } catch (e) {
    // 权限拒绝抛出的错误向上传递;其他错误包装
    if (e instanceof Error && e.message.includes('相册权限')) throw e;
    throw new Error('选择照片失败，请重试');
  }
}