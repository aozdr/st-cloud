import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Cloud, Server, ArrowLeft, CheckCircle2, Loader2 } from 'lucide-react';
import { getServerUrl, setServerUrl, normalize } from '../lib/server-config';
import { updateApiBaseUrl } from '../lib/api';
import { useToast } from '../components/ui/Toast';

export default function ServerConfigPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [url, setUrl] = useState('');
  const [loading, setLoading] = useState(true);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);

  useEffect(() => {
    getServerUrl().then((u) => {
      setUrl(u);
      setLoading(false);
    });
  }, []);

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    try {
      const base = normalize(url);
      const res = await fetch(base + '/api/auth/ping', { method: 'GET', signal: controller.signal });
      if (res.ok) {
        setTestResult({ ok: true, message: '连接成功（服务器响应 ' + res.status + '）' });
      } else {
        setTestResult({ ok: false, message: '服务器已可达，但响应异常（HTTP ' + res.status + '）' });
      }
    } catch (e) {
      const msg = e instanceof DOMException && e.name === 'AbortError' ? '连接超时，请确认地址与端口' : (e instanceof Error ? e.message : '无法连接服务器');
      setTestResult({ ok: false, message: msg });
    } finally {
      clearTimeout(timer);
      setTesting(false);
    }
  };

  const handleSave = async (e: FormEvent) => {
    e.preventDefault();
    await setServerUrl(url);
    updateApiBaseUrl();
    showToast('服务器地址已保存', 'success');
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-screen bg-surface-2">
        <Loader2 className="w-8 h-8 animate-spin text-primary-600" />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-surface-2 flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        {/* Brand */}
        <div className="flex items-center gap-2.5 mb-8 justify-center">
          <div className="w-10 h-10 bg-primary-600 rounded-xl flex items-center justify-center">
            <Cloud className="w-6 h-6 text-white" />
          </div>
          <span className="text-xl font-semibold text-fg">星云盘</span>
        </div>

        <div className="bg-surface rounded-xl border border-border shadow-sm p-6">
          <div className="flex items-center gap-2 mb-1">
            <Server className="w-5 h-5 text-primary-600" />
            <h1 className="text-lg font-semibold text-fg">服务器设置</h1>
          </div>
          <p className="text-sm text-muted mb-5">配置星云盘服务端地址</p>

          <form onSubmit={handleSave} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-muted mb-1.5">服务器地址</label>
              <input
                type="text"
                value={url}
                onChange={(e) => {
                  setUrl(e.target.value);
                  setTestResult(null);
                }}
                placeholder="http://127.0.0.1:8080"
                className="input-field"
                autoFocus
              />
              <p className="mt-1.5 text-xs text-muted">输入服务端 IP 和端口，如 http://192.168.1.100:8080</p>
            </div>

            {testResult && (
              <div
                className={'flex items-center gap-2 px-3 py-2.5 rounded-lg text-sm ' + (testResult.ok ? 'bg-green-500/15 text-green-700 border border-green-200' : 'bg-red-500/15 text-red-700 border border-red-200')}
              >
                {testResult.ok ? (
                  <CheckCircle2 className="w-4 h-4 flex-shrink-0" />
                ) : (
                  <span className="flex-shrink-0">!</span>
                )}
                <span>{testResult.message}</span>
              </div>
            )}

            <div className="flex gap-2">
              <button
                type="button"
                onClick={handleTest}
                disabled={testing || !url.trim()}
                className="btn-secondary flex-1 flex items-center justify-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {testing ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
                {testing ? '测试中…' : '测试连接'}
              </button>
              <button type="submit" className="btn-primary flex-1">
                保存
              </button>
            </div>
          </form>

          <button
            onClick={() => navigate('/login')}
            className="mt-5 flex items-center gap-1.5 text-sm text-muted hover:text-fg cursor-pointer"
          >
            <ArrowLeft className="w-4 h-4" />
            返回登录
          </button>
        </div>
      </div>
    </div>
  );
}
