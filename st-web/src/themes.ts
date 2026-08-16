export type ThemeKey = 'red' | 'blue' | 'emerald' | 'violet' | 'orange' | 'teal';

export interface ThemePalette {
  key: ThemeKey;
  label: string;
  /** 50-950 色阶，值为 "R G B" 空格分隔格式 */
  shades: Record<string, string>;
  /** 用于色块预览的主色（600 色阶 hex） */
  preview: string;
}

export const THEMES: ThemePalette[] = [
  {
    key: 'red',
    label: '红色',
    shades: {
      '50': '254 242 243',
      '100': '253 230 231',
      '200': '250 203 206',
      '300': '245 160 165',
      '400': '237 101 108',
      '500': '227 59 67',
      '600': '217 39 46',
      '700': '185 30 36',
      '800': '151 24 29',
      '900': '125 22 27',
      '950': '68 10 12',
    },
    preview: '#D9272E',
  },
  {
    key: 'blue',
    label: '蓝色',
    shades: {
      '50': '235 244 255',
      '100': '214 231 255',
      '200': '173 206 255',
      '300': '122 173 255',
      '400': '82 134 255',
      '500': '48 110 255',
      '600': '23 92 255',
      '700': '19 76 214',
      '800': '17 64 178',
      '900': '14 52 143',
      '950': '9 33 90',
    },
    preview: '#306EFF',
  },
  {
    key: 'emerald',
    label: '翠绿',
    shades: {
      '50': '236 253 245',
      '100': '209 250 229',
      '200': '167 243 208',
      '300': '110 231 183',
      '400': '52 211 153',
      '500': '16 185 129',
      '600': '5 150 105',
      '700': '4 120 87',
      '800': '6 95 70',
      '900': '6 78 59',
      '950': '2 44 34',
    },
    preview: '#059669',
  },
  {
    key: 'violet',
    label: '紫色',
    shades: {
      '50': '245 243 255',
      '100': '237 233 254',
      '200': '221 214 254',
      '300': '196 181 253',
      '400': '167 139 250',
      '500': '139 92 246',
      '600': '124 58 237',
      '700': '109 40 217',
      '800': '91 33 182',
      '900': '76 29 149',
      '950': '46 16 101',
    },
    preview: '#7C3AED',
  },
  {
    key: 'orange',
    label: '橙色',
    shades: {
      '50': '255 247 237',
      '100': '255 237 213',
      '200': '254 215 170',
      '300': '253 186 116',
      '400': '251 146 60',
      '500': '249 115 22',
      '600': '234 88 12',
      '700': '194 65 12',
      '800': '154 52 18',
      '900': '124 45 18',
      '950': '67 20 7',
    },
    preview: '#EA580C',
  },
  {
    key: 'teal',
    label: '青色',
    shades: {
      '50': '240 253 250',
      '100': '204 251 241',
      '200': '153 246 228',
      '300': '94 234 212',
      '400': '45 212 191',
      '500': '20 184 166',
      '600': '13 148 136',
      '700': '15 118 110',
      '800': '17 94 89',
      '900': '19 78 74',
      '950': '4 47 46',
    },
    preview: '#0D9488',
  },
];

export const DEFAULT_THEME: ThemeKey = 'violet';

/** 将主题色板写入 CSS 变量到 document.documentElement */
export function applyThemeToDOM(palette: ThemePalette) {
  const root = document.documentElement;
  for (const [shade, value] of Object.entries(palette.shades)) {
    root.style.setProperty(`--color-primary-${shade}`, value);
  }
}

export function getThemeByKey(key: ThemeKey): ThemePalette {
  return THEMES.find((t) => t.key === key) ?? THEMES[0];
}
