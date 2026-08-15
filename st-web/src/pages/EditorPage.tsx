import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AlertCircle, ArrowLeft, FileText } from 'lucide-react';
import { Dialog } from '../components/ui/Dialog';
import { getEditorConfig, loadOnlyOfficeApi } from '../lib/editor';
import type { OnlyOfficeConfig } from '../types';

type EditorPhase = 'loading' | 'ready' | 'error';

/** 诊断信息：初始化失败时展示，帮助定位 OnlyOffice 白屏/加载问题 */
type EditorDiagnostics = {
  hasDocsApi: boolean;
  editorUrl: string;
  tokenPreview: string;
  windowErrors: string[];
};

/** 加载骨架：编辑器初始化期间占位，避免白屏 */
function EditorLoadingSkeleton() {
  return (
    <div className="absolute inset-0 flex items-center justify-center bg-surface">
      <div className="w-full max-w-lg px-8">
        <div className="w-16 h-16 bg-surface-2 rounded-2xl shimmer mx-auto mb-5" />
        <div className="h-4 bg-surface-2 rounded shimmer w-3/4 mx-auto mb-3" />
        <div className="h-3 bg-surface-2 rounded shimmer w-1/2 mx-auto mb-6" />
        <div className="h-8 bg-surface-2 rounded shimmer" />
        <p className="text-center text-xs text-muted mt-6">正在打开编辑器…</p>
      </div>
    </div>
  );
}

/**
 * 在线文档编辑页（OnlyOffice 全屏 iframe）。
 * 流程：GET /file/{nodeId}/editor/config → 动态加载 api.js → new DocsAPI.DocEditor。
 * config 失败时：错误弹窗 + 「以预览打开」回退（返回文件列表并打开既有预览）。
 */
export default function EditorPage() {
  const { nodeId } = useParams<{ nodeId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const containerRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<{ destroyEditor?: () => void } | null>(null);
  const [phase, setPhase] = useState<EditorPhase>('loading');
  const [errorMessage, setErrorMessage] = useState('');
  const [fileName, setFileName] = useState('');
  const [diagnostics, setDiagnostics] = useState<EditorDiagnostics | null>(null);
  const windowErrorsRef = useRef<string[]>([]);
  const lastConfigRef = useRef<{ editorUrl: string; token: string } | null>(null);

  // 捕获全局 JS 错误/未处理 Promise 拒绝，进入诊断面板
  useEffect(() => {
    const onErr = (event: ErrorEvent) => {
      windowErrorsRef.current.push(`${event.message} @ ${event.filename}:${event.lineno}`);
    };
    const onRej = (event: PromiseRejectionEvent) => {
      windowErrorsRef.current.push(`unhandled rejection: ${String(event.reason)}`);
    };
    window.addEventListener('error', onErr);
    window.addEventListener('unhandledrejection', onRej);
    return () => {
      window.removeEventListener('error', onErr);
      window.removeEventListener('unhandledrejection', onRej);
    };
  }, []);

  // 打开模式：?mode=view 为 OnlyOffice 只读查看（Office 文件预览），默认编辑
  const mode: 'edit' | 'view' = searchParams.get('mode') === 'view' ? 'view' : 'edit';

  // 来源路径：从文件列表「在线编辑/预览」进入时记录，关闭/回退后返回原目录
  const fromPath =
    (location.state as { from?: string } | null)?.from ??
    searchParams.get('from') ??
    '/files';

  const goBack = useCallback(() => {
    navigate(fromPath);
  }, [navigate, fromPath]);

  // 错误回退：回到文件列表并自动打开该文件的只读预览
  const openPreviewFallback = useCallback(() => {
    navigate(fromPath, { state: { openPreview: nodeId } });
  }, [navigate, fromPath, nodeId]);

  useEffect(() => {
    if (!nodeId) {
      setErrorMessage('缺少文件标识，无法打开编辑器');
      setPhase('error');
      return;
    }

    let cancelled = false;
    let docEditor: { destroyEditor?: () => void } | null = null;

    (async () => {
      try {
        // 1) 获取后端下发的编辑器配置（含权限判定与 JWT token）
        const res = await getEditorConfig(nodeId, mode);
        if (cancelled || !containerRef.current) return;
        lastConfigRef.current = { editorUrl: res.editorUrl, token: res.config?.token ?? '' };
        setFileName(res.config?.document?.title ?? '');

        // 2) 从 editorUrl 动态加载 OnlyOffice api.js
        await loadOnlyOfficeApi(res.editorUrl);
        if (cancelled || !containerRef.current || !window.DocsAPI?.DocEditor) {
          throw new Error('OnlyOffice 编辑器组件加载失败');
        }

        // 3) 初始化编辑器：事件回调为前端本地注册，不参与后端 token 签名
        const config: OnlyOfficeConfig = {
          ...res.config,
          events: {
            ...(res.config.events ?? {}),
            // 用户点击 OnlyOffice 内置返回/关闭按钮时回到文件列表
            onRequestClose: () => {
              if (!cancelled) goBack();
            },
            onError: (err: unknown) => {
              console.error('OnlyOffice editor error:', err);
              windowErrorsRef.current.push(`OnlyOffice editor error: ${String(err)}`);
              setPhase('error');
              setErrorMessage(`OnlyOffice 编辑器报错: ${String(err)}`);
            },
          },
        };
        // OnlyOffice 9.x 实测：DocEditor 传 DOM 元素时不创建 iframe（白屏），传元素 id 字符串正常。
        // 使用固定 id 传参，容器 ref 仅用于尺寸校正。
        docEditor = window.DocsAPI.DocEditor('onlyoffice-editor-container', config);
        editorRef.current = docEditor;
        setPhase('ready');
      } catch (err) {
        if (cancelled) return;
        console.error('编辑器初始化失败:', err);
        const message = err instanceof Error ? err.message : String(err);
        setErrorMessage(message);
        setDiagnostics({
          hasDocsApi: Boolean(window.DocsAPI?.DocEditor),
          editorUrl: lastConfigRef.current?.editorUrl ?? '',
          tokenPreview: lastConfigRef.current?.token
            ? lastConfigRef.current.token.slice(0, 24) + '…'
            : '(无 token)',
          windowErrors: windowErrorsRef.current.slice(-5),
        });
        setPhase('error');
      }
    })();

    // 卸载时销毁编辑器实例，避免重复挂载导致 OnlyOffice 状态残留
    return () => {
      cancelled = true;
      try {
        docEditor?.destroyEditor?.();
      } catch {
        // 销毁异常不影响页面导航
      }
      editorRef.current = null;
    };
  }, [nodeId, goBack, mode]);

  // OnlyOffice 9.x 高度塌缩修复：DocEditor 生成的 iframe 在百分比高度下只渲染工具栏高度（实测 150px），
  // 需按容器实际像素高度强制校正（固定像素生效，百分比不生效）。
  useEffect(() => {
    if (phase !== 'ready') return;
    const fixIframeSize = () => {
      const iframe = document.querySelector<HTMLIFrameElement>('iframe[src*="web-apps"]');
      if (!iframe) return;
      // DocEditor 用 id 传参后会替换掉容器节点，containerRef 指向已卸载节点（clientHeight=0），
      // 不能再用它计算高度；改为视口高度减去顶部工具栏（约 49px）。
      const height = Math.max(window.innerHeight - 49, 300);
      iframe.style.width = '100%';
      iframe.style.height = `${height}px`;
    };
    fixIframeSize();
    const timer = window.setInterval(fixIframeSize, 800);
    window.addEventListener('resize', fixIframeSize);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('resize', fixIframeSize);
    };
  }, [phase]);

  return (
    <div className="h-screen flex flex-col bg-bg">
      {/* h-screen 而非 h-full：EditorPage 位于 AppLayout 之外，父级 Provider 无高度，h-full 会塌缩 */}
      {/* 顶部工具栏：全屏编辑器仅保留返回入口与文件名 */}
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
          <span className="text-sm font-medium text-fg truncate">{fileName || '在线文档编辑'}</span>
        </div>
        <span className="ml-auto text-xs text-muted flex-shrink-0">
          {mode === 'view' ? '只读预览' : '在线编辑'}
        </span>
      </div>

      {/* 编辑器主体：占满剩余空间，OnlyOffice iframe 由 api.js 注入 */}
      <div className="flex-1 min-h-0 relative">
        <div ref={containerRef} id="onlyoffice-editor-container" className="absolute inset-0" />
        {phase === 'loading' && <EditorLoadingSkeleton />}
      </div>

      {/* config/加载失败：错误弹窗 + 「以预览打开」回退 */}
      {phase === 'error' && (
        <Dialog
          title="编辑服务暂不可用"
          description={errorMessage || '无法连接在线编辑服务，请稍后重试。'}
          onClose={goBack}
          footer={
            <>
              <button onClick={openPreviewFallback} className="btn-primary">以预览打开</button>
              <button onClick={goBack} className="btn-secondary">返回文件列表</button>
            </>
          }
        >
          <div className="flex items-start gap-3">
            <AlertCircle className="w-5 h-5 text-amber-500 flex-shrink-0 mt-0.5" aria-hidden />
            <p className="text-sm text-muted">文件内容未受影响，你可以先用只读预览查看，或返回文件列表。</p>
          </div>
          {diagnostics && (
            <div className="mt-3 rounded-lg bg-surface-2 p-3 text-xs text-muted font-mono whitespace-pre-wrap">
              {`DocsAPI=${diagnostics.hasDocsApi}\neditorUrl=${diagnostics.editorUrl}\n${diagnostics.windowErrors.join('\n')}`}
            </div>
          )}
        </Dialog>
      )}
    </div>
  );
}
