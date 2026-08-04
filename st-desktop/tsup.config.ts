import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/main.ts', 'src/preload.ts'],
  format: ['cjs'],
  platform: 'node',
  target: 'node18',
  outDir: 'dist',
  splitting: false,
  sourcemap: true,
  clean: true,
  external: ['electron', 'sql.js'],
});
