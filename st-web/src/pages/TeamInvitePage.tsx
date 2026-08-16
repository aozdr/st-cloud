import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { CheckCircle2, XCircle, Info, Loader2, FolderOpen } from 'lucide-react';
import api from '../lib/api';

type InviteStatus = 'loading' | 'success' | 'already' | 'invalid' | 'unauthorized';

export default function TeamInvitePage() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const [status, setStatus] = useState<InviteStatus>('loading');
  const [spaceId, setSpaceId] = useState<string | null>(null);

  const joinSpace = useCallback(async () => {
    try {
      const id: string = await api.post(`/team/invite/${code}`);
      setSpaceId(id);
      setStatus('success');
    } catch (e) {
      if ((e as { status?: number } | null)?.status === 401) {
        setStatus('unauthorized');
      } else {
        setStatus('invalid');
      }
    }
  }, [code]);

  useEffect(() => {
    joinSpace();
  }, [joinSpace]);

  const handleLoginRedirect = () => {
    navigate(`/login?redirect=/team/invite/${code}`);
  };

  const handleEnterSpace = () => {
    if (spaceId) navigate(`/team/${spaceId}`);
    else navigate('/team');
  };

  return (
    <div className="flex items-center justify-center min-h-screen bg-surface-2">
      <div className="w-full max-w-sm bg-surface rounded-xl shadow-lg border border-border p-8 text-center">
        {status === 'loading' && (
          <>
            <Loader2 className="w-12 h-12 text-primary-600 mx-auto mb-4 animate-spin" />
            <p className="text-sm text-muted">正在验证邀请...</p>
          </>
        )}

        {status === 'success' && (
          <>
            <CheckCircle2 className="w-12 h-12 text-green-500 mx-auto mb-4" />
            <h2 className="text-base font-semibold text-fg mb-2">加入成功</h2>
            <p className="text-sm text-muted mb-6">已成功加入团队空间</p>
            <button onClick={handleEnterSpace} className="w-full py-2.5 bg-primary-600 text-white text-sm font-medium rounded-md hover:bg-primary-700 transition-colors cursor-pointer flex items-center justify-center gap-1.5">
              <FolderOpen className="w-4 h-4" />
              进入空间
            </button>
          </>
        )}

        {status === 'already' && (
          <>
            <Info className="w-12 h-12 text-blue-500 mx-auto mb-4" />
            <h2 className="text-base font-semibold text-fg mb-2">提示</h2>
            <p className="text-sm text-muted mb-6">您已是该空间的成员</p>
            <button onClick={handleEnterSpace} className="w-full py-2.5 bg-primary-600 text-white text-sm font-medium rounded-md hover:bg-primary-700 transition-colors cursor-pointer">
              进入空间
            </button>
          </>
        )}

        {status === 'invalid' && (
          <>
            <XCircle className="w-12 h-12 text-red-500 mx-auto mb-4" />
            <h2 className="text-base font-semibold text-fg mb-2">链接无效</h2>
            <p className="text-sm text-muted mb-6">邀请链接已过期或被撤销<br />请联系空间管理员获取新链接</p>
            <button onClick={() => navigate('/')} className="w-full py-2.5 bg-surface-2 text-fg text-sm font-medium rounded-md hover:bg-surface-2 transition-colors cursor-pointer border border-border">
              返回首页
            </button>
          </>
        )}

        {status === 'unauthorized' && (
          <>
            <FolderOpen className="w-12 h-12 text-primary-600 mx-auto mb-4" />
            <h2 className="text-base font-semibold text-fg mb-2">团队空间邀请</h2>
            <p className="text-sm text-muted mb-6">您被邀请加入团队空间<br />请先登录后加入</p>
            <button onClick={handleLoginRedirect} className="w-full py-2.5 bg-primary-600 text-white text-sm font-medium rounded-md hover:bg-primary-700 transition-colors cursor-pointer">
              登录后加入
            </button>
          </>
        )}
      </div>
    </div>
  );
}
