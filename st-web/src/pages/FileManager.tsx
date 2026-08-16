import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import FileBrowser from '../components/file/FileBrowser';
import { personalFileSource } from '../lib/fileSource';
import type { FileNode } from '../types';

/**
 * 文件管理器：单面板文件浏览
 */
export default function FileManager() {
  const { parentId: parentIdParam } = useParams();
  const parentId = parentIdParam ?? '0';
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const focusId = searchParams.get('focusId');

  return (
    <div className="h-full overflow-hidden">
      <FileBrowser
        source={personalFileSource}
        parentId={parentId}
        focusId={focusId}
        onNavigateFolder={(node: FileNode) => navigate(
          node.id === '0' ? '/files' : `/files/${node.id}`,
          // 携带目标节点路径：FileBrowser 可即时更新面包屑，不等额外请求
          { state: { nodeId: node.id, nodePath: node.path } },
        )}
        onBack={() => navigate(-1)}
        enableShare
        enableVersions
        syncUrl
      />
    </div>
  );
}
