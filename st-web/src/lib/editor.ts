import api from './api';
import type { EditorConfigResponse, OnlyOfficeConfig } from '../types';

/** 支持 OnlyOffice 在线编辑的文件扩展名：仅 docx/xlsx/pptx */
export const EDITABLE_SUFFIXES = ['docx', 'xlsx', 'pptx'] as const;

/** 判断文件后缀是否支持在线编辑（大小写不敏感） */
export function isEditableOfficeSuffix(suffix: string | null | undefined): boolean {
  if (!suffix) return false;
  return (EDITABLE_SUFFIXES as readonly string[]).includes(suffix.toLowerCase());
}

/**
 * 获取编辑器配置：{editorUrl, config}。
 * config 已含后端签发的 JWT token，直接传给 DocsAPI.DocEditor；
 * 编辑/只读权限以后端判定为准，前端仅做入口展示。
 * @param mode edit=编辑模式（默认）；view=只读查看模式（Office 文件预览）
 */
export function getEditorConfig(nodeId: string, mode: 'edit' | 'view' = 'edit'): Promise<EditorConfigResponse> {
  return api.get<EditorConfigResponse>(`/file/${nodeId}/editor/config`, {
    params: mode === 'view' ? { mode: 'view' } : undefined,
  });
}

declare global {
  interface Window {
    DocsAPI?: {
      DocEditor: (
        placeholder: string | HTMLElement,
        config: OnlyOfficeConfig,
      ) => { destroyEditor?: () => void };
    };
  }
}

/**
 * 动态加载 OnlyOffice 前端 API（api.js）。
 * 同一页面只注入一次；重复进入编辑器时复用已加载的脚本。
 * 地址由后端下发的 editorUrl 推导：{editorUrl}/web-apps/apps/api/documents/api.js
 */
export function loadOnlyOfficeApi(editorUrl: string): Promise<void> {
  const base = (editorUrl || '').replace(/\/+$/, '');
  const src = `${base}/web-apps/apps/api/documents/api.js`;
  if (window.DocsAPI?.DocEditor) return Promise.resolve();

  // 清理上次失败残留的脚本元素，避免事件丢失导致 Promise 永久挂起（重进页面可重试）
  document.querySelectorAll('script[data-onlyoffice-api]').forEach((s) => s.remove());

  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = src;
    script.async = true;
    script.dataset.onlyofficeApi = '1';
    const timer = window.setTimeout(() => {
      script.remove();
      reject(new Error(`OnlyOffice API 加载超时: ${src}`));
    }, 15000);
    script.onload = () => {
      window.clearTimeout(timer);
      resolve();
    };
    script.onerror = () => {
      window.clearTimeout(timer);
      script.remove();
      reject(new Error(`OnlyOffice API 加载失败: ${src}`));
    };
    document.head.appendChild(script);
  });
}
