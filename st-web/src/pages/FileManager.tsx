import { useParams, useNavigate } from 'react-router-dom';
import FileBrowser from '../components/file/FileBrowser';
import { personalFileSource } from '../lib/fileSource';
import type { FileNode } from '../types';

export default function FileManager() {
  const { parentId: parentIdParam } = useParams();
  const parentId = parentIdParam ?? '0';
  const navigate = useNavigate();

  return (
    <FileBrowser
      key={parentId}
      source={personalFileSource}
      parentId={parentId}
      onNavigateFolder={(node: FileNode) => navigate(`/files/${node.id}`)}
      onBack={() => navigate(-1)}
      enableShare
      enableVersions
    />
  );
}
