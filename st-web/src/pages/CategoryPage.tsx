import { useParams } from 'react-router-dom';
import FileBrowser from '../components/file/FileBrowser';
import { categoryFileSource } from '../lib/fileSource';
import { FILE_CATEGORIES } from '../lib/fileTypes';

export default function CategoryPage() {
  const { type } = useParams<{ type: string }>();
  const category = FILE_CATEGORIES.find((c) => c.type === type);

  if (!category) {
    return (
      <div className="flex items-center justify-center h-full text-muted text-sm">
        未知分类
      </div>
    );
  }

  return (
    <FileBrowser
      key={category.type}
      source={categoryFileSource(category)}
      parentId={null}
      onNavigateFolder={() => {}}
      categoryLabel={category.label}
      enableShare
      enableVersions
    />
  );
}