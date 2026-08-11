import { useNavigate } from 'react-router-dom';
import FileBrowser from '../components/file/FileBrowser';
import { favoriteFileSource } from '../lib/fileSource';
import type { FileNode } from '../types';

/**
 * 我的收藏页面：复用 FileBrowser，数据源为收藏列表接口。
 * 收藏页禁用上传/新建文件夹等无意义操作，仅支持浏览与已有文件操作。
 */
export default function FavoritesPage() {
  const navigate = useNavigate();

  /** 双击文件夹时跳转到文件管理器对应目录 */
  const handleNavigateFolder = (node: FileNode) => {
    navigate(`/files/${node.id}`);
  };

  return (
    <FileBrowser
      source={favoriteFileSource()}
      parentId={null}
      onNavigateFolder={handleNavigateFolder}
      categoryLabel="我的收藏"
      enableShare
      enableVersions
    />
  );
}
