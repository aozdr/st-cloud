import { useState } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { Columns2, Square } from 'lucide-react';
import FileBrowser from '../components/file/FileBrowser';
import { personalFileSource } from '../lib/fileSource';
import type { FileNode } from '../types';

/**
 * 文件管理器：支持单面板/双面板切换
 * 双面板模式下左右两栏独立浏览，方便跨文件夹操作
 */
export default function FileManager() {
  const { parentId: parentIdParam } = useParams();
  const parentId = parentIdParam ?? '0';
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const focusId = searchParams.get('focusId');
  const [dualPanel, setDualPanel] = useState(false);
  const [rightParentId, setRightParentId] = useState('0');

  return (
    <div className="h-full flex flex-col">
      {/* 双面板切换按钮 */}
      <div className="flex items-center justify-end px-4 py-1.5 border-b border-border bg-bg">
        <button
          onClick={() => setDualPanel(!dualPanel)}
          className="flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium text-muted hover:text-fg hover:bg-surface-2 rounded-lg cursor-pointer transition-colors"
          title={dualPanel ? '切换为单面板' : '切换为双面板'}
        >
          {dualPanel ? <Square className="w-3.5 h-3.5" /> : <Columns2 className="w-3.5 h-3.5" />}
          <span>{dualPanel ? '单面板' : '双面板'}</span>
        </button>
      </div>

      <div className={`flex-1 min-h-0 flex ${dualPanel ? 'gap-px bg-border' : ''} overflow-hidden`}>
        <div className="flex-1 min-h-0 overflow-hidden">
          <FileBrowser
            key={parentId}
            source={personalFileSource}
            parentId={parentId}
            focusId={focusId}
            onNavigateFolder={(node: FileNode) => navigate(`/files/${node.id}`)}
            onBack={() => navigate(-1)}
            enableShare
            enableVersions
            syncUrl
          />
        </div>
        {dualPanel && (
          <div className="flex-1 overflow-hidden">
            <FileBrowser
              key={`right-${rightParentId}`}
              source={personalFileSource}
              parentId={rightParentId}
              onNavigateFolder={(node: FileNode) => setRightParentId(node.id)}
              onBack={() => navigate(-1)}
              enableShare
              enableVersions
            />
          </div>
        )}
      </div>
    </div>
  );
}
