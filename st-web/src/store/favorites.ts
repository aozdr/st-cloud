import { create } from 'zustand';
import api from '../lib/api';
import type { FileNode } from '../types';

interface FavoritesState {
  /** 收藏的文件节点ID集合，供快速判断收藏状态 */
  favoriteIds: Set<string>;
  /** 收藏的文件列表（含元数据），供首页展示 */
  favorites: FileNode[];
  /** 是否已加载过收藏ID列表 */
  loaded: boolean;

  /** 登录后加载收藏ID列表（轻量） */
  fetchFavoriteIds: () => Promise<void>;
  /** 加载收藏文件列表（含元数据，首页用） */
  fetchFavorites: () => Promise<void>;
  /** 切换收藏状态，同步更新本地 Set */
  toggleFavorite: (node: FileNode) => Promise<boolean>;
  /** 判断是否已收藏 */
  isFavorite: (id: string) => boolean;
  /** 退出登录时重置 */
  reset: () => void;
}

export const useFavoritesStore = create<FavoritesState>((set, get) => ({
  favoriteIds: new Set<string>(),
  favorites: [],
  loaded: false,

  fetchFavoriteIds: async () => {
    try {
      const ids: number[] = await api.get('/favorite/ids');
      set({ favoriteIds: new Set(ids.map(String)), loaded: true });
    } catch {
      set({ loaded: true });
    }
  },

  fetchFavorites: async () => {
    try {
      const list: FileNode[] = await api.get('/favorite/list');
      set({ favorites: list, favoriteIds: new Set(list.map((f) => f.id)), loaded: true });
    } catch {
      // 忽略错误，保持现有状态
    }
  },

  toggleFavorite: async (node) => {
    const added: boolean = await api.post(`/favorite/${node.id}`);
    const ids = new Set(get().favoriteIds);
    if (added) {
      ids.add(node.id);
    } else {
      ids.delete(node.id);
    }
    set({ favoriteIds: ids });
    return added;
  },

  isFavorite: (id) => get().favoriteIds.has(id),

  reset: () => set({ favoriteIds: new Set<string>(), favorites: [], loaded: false }),
}));