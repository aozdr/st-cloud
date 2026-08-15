import { useEffect, useState } from 'react';
import * as QRCodeLib from 'qrcode';

interface Props {
  value: string;
  size?: number;
}

export default function QrCode({ value, size = 200 }: Props) {
  const [dataUrl, setDataUrl] = useState('');

  useEffect(() => {
    let cancelled = false;
    QRCodeLib.toDataURL(value, {
      width: size,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: '#1c1917', light: '#ffffff' },
    })
      .then((url: string) => {
        if (!cancelled) setDataUrl(url);
      })
      .catch(() => {
        if (!cancelled) setDataUrl('');
      });
    return () => {
      cancelled = true;
    };
  }, [value, size]);

  if (!dataUrl) {
    return (
      <div
        style={{ width: size, height: size }}
        className="bg-surface-2 rounded animate-pulse"
      />
    );
  }

  return (
    <img
      src={dataUrl}
      width={size}
      height={size}
      alt="二维码"
      className="rounded"
      style={{ display: 'block' }}
    />
  );
}