import { create } from 'zustand';
import type { SearchResultVO } from '../types';

interface FolderFilterState {
  keyword: string;
  setKeyword: (kw: string) => void;
  folderPath: string;
  setFolderPath: (path: string) => void;
  searchResults: SearchResultVO[];
  setSearchResults: (results: SearchResultVO[]) => void;
}

export const useFolderFilterStore = create<FolderFilterState>((set) => ({
  keyword: '',
  setKeyword: (kw) => set({ keyword: kw }),
  folderPath: '/',
  setFolderPath: (path) => set({ folderPath: path }),
  searchResults: [],
  setSearchResults: (results) => set({ searchResults: results }),
}));