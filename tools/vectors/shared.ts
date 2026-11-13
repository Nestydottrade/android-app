import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Address, Hex } from 'viem';

/** Where the Kotlin tests read them: android/app/src/test/resources/fixtures/evm/. */
export const OUT = fileURLToPath(new URL('../../app/src/test/resources/fixtures/evm/', import.meta.url));

/**
 * MADE-UP test key, the same as keyVectors.json's `evmKeys` sample: never funded, never used
 * on a chain. Only signs vectors here.
 */
export const MADE_UP_KEY: Hex = `0x${'4c1e9a7b'.repeat(8)}`;
export const MADE_UP_WALLET: Address = '0x11064Ffa76d1c861e9c19b3D36F66572f16f9576';
/** A second made-up wallet, for "one of your other wallets". */
export const OTHER_WALLET: Address = '0x00000000000000000000000000000000000Be5A1';
/** Someone who is not the user. */
export const STRANGER: Address = '0x000000000000000000000000000000000000dEaD';

/** JSON with bigints as decimal strings, a fixed key order and a newline at the end. */
export function toJson(value: unknown): string {
  return `${JSON.stringify(value, (_k, v) => (typeof v === 'bigint' ? v.toString() : v), 1)}\n`;
}

export function write(name: string, value: unknown): void {
  const path = join(OUT, name);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, toJson(value));
}

/** A fixed clock for every vector: 2026-11-14 00:00:00 UTC. */
export const NOW_MS = 1_794_614_400_000;
export const NOW_S = NOW_MS / 1000;

/** Where the Kotlin Solana tests read them: android/app/src/test/resources/fixtures/sol/. */
export const OUT_SOL = fileURLToPath(new URL('../../app/src/test/resources/fixtures/sol/', import.meta.url));

export function writeSol(name: string, value: unknown): void {
  const path = join(OUT_SOL, name);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, toJson(value));
}

/**
 * MADE-UP Solana seed (32 bytes), as MADE_UP_KEY: never funded, never used on a chain. It signs
 * offline vectors only, over a made-up blockhash, so nothing it signs could ever land.
 */
export const MADE_UP_SOL_SEED_HEX = '4c1e9a7b'.repeat(8);
/** A second made-up Solana address (32 bytes of 0x0b), for "one of your other wallets". */
export const OTHER_SOL = 'k7FaK87WHGVXzkaoHb7CdVPgkKDQhZ29VLDeBVbDfYn';
/** Someone who is not the user (32 bytes of 0x0d). */
export const STRANGER_SOL = 'swqrv48gsrwpBFbftEwnP2vB4jckpvfGJfXkwaniLCC';
/** A made-up blockhash (32 bytes of 0x07): no transaction naming it can land. */
export const MADE_UP_BLOCKHASH = 'US517G5965aydkZ46HS38QLi7UQiSojurfbQfKCELFx';
