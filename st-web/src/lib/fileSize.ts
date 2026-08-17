/**
 * 文件大小格式化工具
 *
 * 将字节数转换为可读的文件大小字符串，支持 B / KB / MB / GB / TB 五种单位。
 * 采用 1024 进制换算（1 KB = 1024 B），与操作系统 / 文件系统惯例一致。
 * KB 及以上单位保留 1 位小数；B 单位显示整数（避免出现 "512.0 B" 的冗余展示）；
 * 0 值（含非法输入）统一显示为 "0 B"。
 */
export function formatFileSize(bytes: number): string {
  // 单位列表，按下标从小到大对应 B / KB / MB / GB / TB
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];

  // 非法输入（NaN / Infinity）与小于等于 0 的值统一按 0 处理
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '0 B';
  }

  // 从字节开始，每满 1024 向上进位一次，直到数值小于 1024 或到达最大单位 TB
  let size = bytes;
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex += 1;
  }

  // B 单位显示整数（四舍五入），其余单位保留 1 位小数（toFixed 自动四舍五入）
  if (unitIndex === 0) {
    return `${Math.round(size)} ${units[unitIndex]}`;
  }
  return `${size.toFixed(1)} ${units[unitIndex]}`;
}
