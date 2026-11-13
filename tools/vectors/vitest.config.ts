import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';
import { WxtVitest } from 'wxt/testing/vitest-plugin';

/**
 * Writes the Kotlin engine's test vectors with the extension's own TypeScript engine, the
 * reference for every byte (docs/android-brief.md, "Port with vectors"). Run from the
 * extension's folder:
 *
 *   pnpm vitest run --config android/tools/vectors/vitest.config.ts
 *
 * `replay.test.ts` reads live chains (read-only eth_call, nothing is signed or sent) and is
 * left out unless VECTORS_LIVE=1.
 */
const root = fileURLToPath(new URL('../../..', import.meta.url));

export default defineConfig({
  root,
  plugins: [WxtVitest()],
  test: {
    root,
    include: ['android/tools/vectors/**/*.test.ts'],
    exclude: process.env.VECTORS_LIVE === '1' ? [] : ['android/tools/vectors/replay.test.ts', 'android/tools/vectors/solana-replay.test.ts'],
    environment: 'node',
    testTimeout: 600_000,
    hookTimeout: 600_000,
  },
});
