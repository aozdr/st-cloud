import { useCallback, useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, FileText, Save } from 'lucide-react';
import api from '../lib/api';
import { useToast } from '../components/ui/Toast';
import { isElectron } from '../lib/electron';
import { getServerUrlSync } from '../lib/server-config';

/**
 * 应用内轻量文本编辑器（方案 B）：txt/md/代码等文本文件在线编辑，
 * 保存走 /api/file/{nodeId}/text-content（个人 owner；团队走团队接口）。
 */
export default function TextEditorPage() {
  const { nodeId } = useParams<{ nodeId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { showToast } = useToast();
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const fileName = (location.state as { name?: string } | null)?.name ?? '文本编辑';
  const fromPath = (location.state as { from?: string } | null)?.from ?? '/files';
  const spaceId = (location.state as { spaceId?: string } | null)?.spaceId;
  const goBack = useCallback(() => navigate(fromPath), [navigate, fromPath]);

  // 加载文本内容（stream 端点，带 token；与预览取数同链路）
  useEffect(() => {
    if (!nodeId) return;
    let cancelled = false;
    const controller = new AbortController();
    const token = localStorage.getItem('accessToken');
    const timer = window.setTimeout(() => controller.abort(), 15000);
    fetch(`${isElectron() ? getServerUrlSync() : ''}/api/file/${nodeId}/stream`, {
      headers: { Authorization: `Bearer ${token}` },
      signal: controller.signal,
    })
      .then((res) => {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.text();
      })
      .then((text) => {
        if (!cancelled) {
          window.clearTimeout(timer);
          setContent(text);
          setLoading(false);
        }
      })
      .catch((err) => {
        console.error('text load failed:', err);
        if (!cancelled) {
          window.clearTimeout(timer);
          showToast('加载失败：' + String(err instanceof Error ? err.message : err), 'error');
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [nodeId, showToast]);

  // 未保存离开确认
  useEffect(() => {
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirty]);

  const handleSave = async () => {
    if (!nodeId || saving) return;
    setSaving(true);
    try {
      // 团队文件走团队接口（edit 权限点校验），个人文件走个人接口（owner 校验）
      const url = spaceId
        ? `/team/${spaceId}/files/${nodeId}/text-content`
        : `/file/${nodeId}/text-content`;
      await api.put(url, { content });
      setDirty(false);
      showToast('已保存');
    } catch (err) {
      showToast(err instanceof Error ? err.message : '保存失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="h-screen flex flex-col bg-bg">
      {/* 顶部工具栏：返回 + 文件名 + 保存 */}
      <div className="flex items-center gap-2 px-4 py-2 border-b border-border bg-surface flex-shrink-0">
        <button
          onClick={goBack}
          aria-label="返回"
          className="flex items-center gap-1.5 text-sm text-muted hover:text-fg cursor-pointer px-2 py-1.5 rounded-lg hover:bg-surface-2 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ArrowLeft className="w-4 h-4" aria-hidden />
          <span>返回</span>
        </button>
        <div className="flex items-center gap-2 min-w-0">
          <FileText className="w-4 h-4 text-muted flex-shrink-0" aria-hidden />
          <span className="text-sm font-medium text-fg truncate">{fileName}</span>
          {dirty && <span className="text-xs text-amber-500 flex-shrink-0">未保存</span>}
        </div>
        <button
          onClick={handleSave}
          disabled={saving || !dirty || loading}
          className="ml-auto flex items-center gap-1.5 px-3 py-1.5 text-sm text-white bg-primary-600 rounded-md hover:bg-primary-700 cursor-pointer transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <Save className="w-4 h-4" aria-hidden />
          {saving ? '保存中…' : '保存'}
        </button>
      </div>

      {/* 编辑区：等宽字体文本域 */}
      <div className="flex-1 min-h-0">
        {loading ? (
          <div className="h-full flex items-center justify-center">
            <div className="w-8 h-8 border-2 border-border border-t-primary-500 rounded-full animate-spin" />
          </div>
        ) : (
          <textarea
            value={content}
            onChange={(e) => {
              setContent(e.target.value);
              setDirty(true);
            }}
            spellCheck={false}
            className="w-full h-full p-5 bg-bg text-sm font-mono leading-relaxed outline-none resize-none"
            placeholder="文件内容为空…"
          />
        )}
      </div>
    </div>
  );
}
