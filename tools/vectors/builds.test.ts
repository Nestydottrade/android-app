import { test } from 'vitest';
import { encodeFunctionData, maxUint256, zeroAddress, type Address, type Hex } from 'viem';
import { privateKeyToAccount } from 'viem/accounts';
import { getChain, type ChainConfig } from '../../../src/shared/chains';
import { erc20WriteAbi } from '../../../src/background/chain/abis';
import { launchpadRules, launchpadVenues, MONTRA_HOOKS } from '../../../src/background/trade/launchpads';
import {
  ROUTER_NATIVE,
  TRANSFER_PERMIT_TYPES,
  buildTransferPermit,
  checkTransferPermit,
  encodeRouterExecute,
  nestyRouterRule,
  routerBuyCalls,
  routerSellCalls,
  type FundedRoute,
  type RouterCall,
} from '../../../src/background/trade/nesty-router';
import { PERMIT_TYPES, buildPermit, permitDomain } from '../../../src/background/trade/permit2';
import { priceImpactBps } from '../../../src/background/trade/routing';
import type { PoolRef, SellParams, TxDraft, Venue, VenueCtx } from '../../../src/background/trade/types';
import { PERMIT2, UNISWAP } from '../../../src/background/trade/uniswap/addresses';
import {
  ADDRESS_THIS,
  MSG_SENDER,
  NATIVE,
  encodeExecute,
  type Command,
  type PermitSingle,
  type PoolKey,
} from '../../../src/background/trade/uniswap/codec';
import { poolIdOf, uniswapVenue } from '../../../src/background/trade/uniswap/venue';
import {
  validatePermit,
  validateTx,
  type ExtraContractRule,
  type ValidationContext,
} from '../../../src/background/trade/validator';
import { extraVenues, venueRules } from '../../../src/background/trade/venues';
import { approvalTarget, minOutAfter } from '../../../src/background/trade/engine/trading';
import { expectedFeeWei } from '../../../src/background/trade/engine/core';
import { computeFees, staticGas, type GasKind } from '../../../src/background/trade/fees';
import { DEFAULT_GLOBAL_SETTINGS } from '../../../src/shared/settings/defaults';
import { MADE_UP_KEY, MADE_UP_WALLET, NOW_MS, NOW_S, OTHER_WALLET, STRANGER, write } from './shared';

/**
 * Calldata of every venue's buys and sells, Nesty Router calls, and the validator's verdicts on
 * them and on tampered copies, as the TypeScript engine makes them. Pools are shaped like the
 * ones discovery finds; their addresses are real contracts where the venue names one, made up
 * (and marked so) where a coin or pool stands in.
 */

const account = privateKeyToAccount(MADE_UP_KEY);
const VENUES: Venue[] = [uniswapVenue, ...extraVenues, ...launchpadVenues];
const venue = (id: string) => {
  const v = VENUES.find((x) => x.id === id);
  if (!v) throw new Error(`no venue ${id}`);
  return v;
};
const chainOf = (id: number) => getChain(id) as ChainConfig;
const ctxOf = (chainId: number): VenueCtx => ({
  chain: chainOf(chainId),
  client: null as never,
  now: () => NOW_MS,
});

// Made-up coins and pools (valid addresses, no contract behind them).
const COIN = {
  bscFour: '0x1111111111111111111111111111111111114444',
  bscFlap: '0x2222222222222222222222222222222222227777',
  baseFlap: '0x3333333333333333333333333333333333337777',
  rhFlap: '0x4444444444444444444444444444444444447777',
  rhPons: '0x5555555555555555555555555555555555555555',
  baseVirtuals: '0x6666666666666666666666666666666666666666',
  elyMontra: '0x7777777777777777777777777777777777777777',
  ethCoin: '0x8888888888888888888888888888888888888888',
  baseCoin: '0x9999999999999999999999999999999999999999',
  bscCoin: '0xaAaAaAaaAaAaAaaAaAAAAAAAAaaaAaAaAaaAaaAa',
} as const satisfies Record<string, Address>;
const POOL = {
  a: '0x00000000000000000000000000000000000000a1',
  b: '0x00000000000000000000000000000000000000b2',
  c: '0x00000000000000000000000000000000000000c3',
  curve: '0x00000000000000000000000000000000000000c4',
} as const satisfies Record<string, Address>;

const BSC = { usd1: '0x8d0D000Ee44948FC98c9B98A4FA4921476f08B0d', usdt: '0x55d398326f99059fF775485246999027B3197955' } as const;
const BASE = { usdc: '0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913', virtual: '0x0b3e328455c4059EEb9e3f84b5543F74E24e7E1b', fleth: '0x000000000D564D5be76f7f0d28fE52605afC7Cf8' } as const;
const RH = { usdg: '0x5fc5360D0400a0Fd4f2af552ADD042D716F1d168' } as const;
const ETH = { usdc: '0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48' } as const;

const sorted = (a: Address, b: Address): [Address, Address] => (BigInt(a) < BigInt(b) ? [a, b] : [b, a]);
const keyOf = (token: Address, other: Address, fee: number, tickSpacing: number, hooks: Address): PoolKey => {
  const [currency0, currency1] = sorted(token, other);
  return { currency0, currency1, fee, tickSpacing, hooks };
};
const v4 = (token: Address, key: PoolKey, quote: Address, label: string, via?: unknown): PoolRef => ({
  venueId: 'uniswap',
  id: poolIdOf(key),
  label,
  token,
  data: { kind: 'v4', poolId: poolIdOf(key), key, quote, ...(via ? { via } : {}) },
});

const W = (chainId: number) => chainOf(chainId).wrappedNative;
const flethKey = (() => {
  const [currency0, currency1] = sorted(NATIVE, BASE.fleth);
  return { currency0, currency1, fee: 0, tickSpacing: 60, hooks: '0x9e433f32bb5481a9ca7dff5b3af74a7ed041a888' as Address };
})();

/** Leg pools: the native ↔ asset pool findLeg would pick (an AMM venue's pool of the asset). */
const LEGS = {
  bscUsd1V3: { venueId: 'pancake-v3-bsc', id: POOL.a, label: 'PancakeSwap v3 · 0.05%', token: BSC.usd1, data: { pool: POOL.a, fee: 500 } },
  bscUsdtV2: { venueId: 'pancake-v2-bsc', id: POOL.b, label: 'PancakeSwap v2', token: BSC.usdt, data: { pair: POOL.b, stable: false } },
  rhUsdgV3: { venueId: 'uniswap', id: POOL.c, label: 'Uniswap v3 · 0.05%', token: RH.usdg, data: { kind: 'v3', pool: POOL.c, quote: W(4663), fee: 500 } },
  rhUsdgV2: { venueId: 'uniswap', id: POOL.b, label: 'Uniswap v2', token: RH.usdg, data: { kind: 'v2', pair: POOL.b, quote: W(4663), token0: RH.usdg } },
  baseVirtual: {
    venueId: 'uniswap',
    id: '0x9c087eb773291e50cf6c6a90ef0f4500e349b903',
    label: 'Uniswap v3',
    token: BASE.virtual,
    data: { kind: 'v3', pool: '0x9c087eb773291e50cf6c6a90ef0f4500e349b903', quote: W(8453), fee: 500 },
  },
  baseUsdcAero: { venueId: 'aerodrome-base', id: POOL.a, label: 'Aerodrome · volatile', token: BASE.usdc, data: { pair: POOL.a, stable: false } },
} satisfies Record<string, PoolRef>;

interface Case {
  name: string;
  chainId: number;
  pool: PoolRef;
}

const montraKey: PoolKey = { currency0: zeroAddress, currency1: COIN.elyMontra, fee: 0x800000, tickSpacing: 200, hooks: MONTRA_HOOKS[0] as Address };

const POOLS: Case[] = [
  // four.meme (BNB Chain): native curve, and a curve in USD1 through its helper.
  { name: 'four native', chainId: 56, pool: { venueId: 'fourmeme', id: `four:${COIN.bscFour.toLowerCase()}`, label: 'four.meme curve', token: COIN.bscFour, data: { feeBps: 100 } } },
  {
    name: 'four in USD1',
    chainId: 56,
    pool: { venueId: 'fourmeme', id: `four:${COIN.bscFour.toLowerCase()}`, label: 'four.meme curve via USD1', token: COIN.bscFour, data: { quote: BSC.usd1, leg: LEGS.bscUsd1V3, feeBps: 100, tax: { buyBps: 300, sellBps: 500 } } },
  },
  // Flap: native on each of its chains (Base's two portals), and an ERC-20 curve.
  { name: 'flap native bsc', chainId: 56, pool: { venueId: 'flap', id: `flap:${COIN.bscFlap.toLowerCase()}`, label: 'Flap curve', token: COIN.bscFlap, data: { portal: '0xe2cE6ab80874Fa9Fa2aAE65D277Dd6B8e65C9De0' } } },
  { name: 'flap native base new', chainId: 8453, pool: { venueId: 'flap', id: `flap:${COIN.baseFlap.toLowerCase()}`, label: 'Flap curve', token: COIN.baseFlap, data: { portal: '0x0000bc1c4fd15dd79029af8f5d77d68ae4490000' } } },
  { name: 'flap native base old', chainId: 8453, pool: { venueId: 'flap', id: `flap:${COIN.baseFlap.toLowerCase()}`, label: 'Flap curve', token: COIN.baseFlap, data: { portal: '0x00214f8C22A076575f4B67f3B4AA62f99E166e36' } } },
  { name: 'flap native robinhood', chainId: 4663, pool: { venueId: 'flap', id: `flap:${COIN.rhFlap.toLowerCase()}`, label: 'Flap curve', token: COIN.rhFlap, data: { portal: '0x26605f322f7ff986f381bb9a6e3f5dab0beaeb09' } } },
  {
    name: 'flap in USDT',
    chainId: 56,
    pool: { venueId: 'flap', id: `flap:${COIN.bscFlap.toLowerCase()}`, label: 'Flap curve via USDT', token: COIN.bscFlap, data: { portal: '0xe2cE6ab80874Fa9Fa2aAE65D277Dd6B8e65C9De0', quote: BSC.usdt, symbol: 'USDT', leg: LEGS.bscUsdtV2 } },
  },
  {
    name: 'flap in USDC base',
    chainId: 8453,
    pool: { venueId: 'flap', id: `flap:${COIN.baseFlap.toLowerCase()}`, label: 'Flap curve via USDC', token: COIN.baseFlap, data: { portal: '0x0000bc1c4fd15dd79029af8f5d77d68ae4490000', quote: BASE.usdc, symbol: 'USDC', leg: LEGS.baseUsdcAero } },
  },
  // Pons (Robinhood): native curve, and a curve in USDG.
  { name: 'pons native', chainId: 4663, pool: { venueId: 'pons', id: `pons:${POOL.curve}`, label: 'Pons curve', token: COIN.rhPons, data: { curve: POOL.curve } } },
  {
    name: 'pons in USDG',
    chainId: 4663,
    pool: { venueId: 'pons', id: `pons:${POOL.curve}`, label: 'Pons curve via USDG', token: COIN.rhPons, data: { curve: POOL.curve, quote: RH.usdg, symbol: 'USDG', leg: LEGS.rhUsdgV3 } },
  },
  {
    name: 'pons in USDG v2 leg',
    chainId: 4663,
    pool: { venueId: 'pons', id: `pons:${POOL.curve}`, label: 'Pons curve via USDG', token: COIN.rhPons, data: { curve: POOL.curve, quote: RH.usdg, symbol: 'USDG', leg: LEGS.rhUsdgV2 } },
  },
  // Virtuals (Base), in VIRTUAL.
  { name: 'virtuals', chainId: 8453, pool: { venueId: 'virtuals', id: `virtuals:${COIN.baseVirtuals.toLowerCase()}`, label: 'Virtuals curve via VIRTUAL', token: COIN.baseVirtuals, data: null } },
  // Montra (Elysium): its own router, one v4 pool for life.
  { name: 'montra', chainId: 99901, pool: { venueId: 'montra', id: `montra:${poolIdOf(montraKey)}`, label: 'Montra', token: COIN.elyMontra, data: { key: montraKey } } },
  // Uniswap: the launch pools (v4, hooks), then v2 and v3, direct and through a stable.
  { name: 'v4 stockereum', chainId: 1, pool: v4(COIN.ethCoin, keyOf(COIN.ethCoin, W(1), 0, 200, '0x322dcEc4958C14e021A9F1cD49DF11b9457968cC'), W(1), 'Uniswap v4 · 0% · hook') },
  { name: 'v4 klik native', chainId: 1, pool: v4(COIN.ethCoin, keyOf(COIN.ethCoin, NATIVE, 0, 200, POOL.a), NATIVE, 'Uniswap v4 · 0% · hook') },
  { name: 'v4 clanker weth', chainId: 8453, pool: v4(COIN.baseCoin, keyOf(COIN.baseCoin, W(8453), 0x800000, 200, POOL.b), W(8453), 'Uniswap v4 · dynamic fee · hook') },
  { name: 'v4 doppler robinhood', chainId: 4663, pool: v4(COIN.rhPons, keyOf(COIN.rhPons, NATIVE, 0x800000, 200, POOL.c), NATIVE, 'Uniswap v4 · dynamic fee · hook') },
  {
    name: 'v4 flaunch via flETH',
    chainId: 8453,
    pool: v4(COIN.baseCoin, keyOf(COIN.baseCoin, BASE.fleth, 0, 60, POOL.a), BASE.fleth, 'Uniswap v4 · 0% · hook via flETH', { stable: BASE.fleth, pool: zeroAddress, fee: 0, v4Key: flethKey }),
  },
  { name: 'v4 zora in ZORA', chainId: 8453, pool: v4(COIN.baseCoin, keyOf(COIN.baseCoin, BASE.usdc, 3000, 60, zeroAddress), BASE.usdc, 'Uniswap v4 · 0.3% via USDC', { stable: BASE.usdc, pool: POOL.c, fee: 500 }) },
  { name: 'v4 pons graduated', chainId: 4663, pool: v4(COIN.rhPons, keyOf(COIN.rhPons, NATIVE, 0, 200, POOL.a), NATIVE, 'Uniswap v4 · 0% · hook') },
  { name: 'v2 direct', chainId: 1, pool: { venueId: 'uniswap', id: POOL.a, label: 'Uniswap v2', token: COIN.ethCoin, data: { kind: 'v2', pair: POOL.a, quote: W(1), token0: COIN.ethCoin } } },
  { name: 'v2 via USDC', chainId: 1, pool: { venueId: 'uniswap', id: POOL.a, label: 'Uniswap v2 via USDC', token: COIN.ethCoin, data: { kind: 'v2', pair: POOL.a, quote: ETH.usdc, token0: ETH.usdc, via: { stable: ETH.usdc, pool: POOL.b, fee: 500 } } } },
  { name: 'v3 direct', chainId: 8453, pool: { venueId: 'uniswap', id: POOL.a, label: 'Uniswap v3 · 1%', token: COIN.baseCoin, data: { kind: 'v3', pool: POOL.a, quote: W(8453), fee: 10000 } } },
  { name: 'v3 via USDC', chainId: 8453, pool: { venueId: 'uniswap', id: POOL.a, label: 'Uniswap v3 · 1% via USDC', token: COIN.baseCoin, data: { kind: 'v3', pool: POOL.a, quote: BASE.usdc, fee: 10000, via: { stable: BASE.usdc, pool: POOL.b, fee: 500 } } } },
  { name: 'v3 via VIRTUAL', chainId: 8453, pool: { venueId: 'uniswap', id: POOL.a, label: 'Uniswap v3 · 1% via VIRTUAL', token: COIN.baseCoin, data: { kind: 'v3', pool: POOL.a, quote: BASE.virtual, fee: 10000, via: { stable: BASE.virtual, pool: '0x9c087eb773291e50cf6c6a90ef0f4500e349b903', fee: 500 } } } },
  { name: 'v2 robinhood', chainId: 4663, pool: { venueId: 'uniswap', id: POOL.b, label: 'Uniswap v2', token: COIN.rhPons, data: { kind: 'v2', pair: POOL.b, quote: W(4663), token0: W(4663) } } },
  { name: 'v3 elysium', chainId: 99901, pool: { venueId: 'uniswap', id: POOL.a, label: 'Uniswap v3 · 0.3%', token: COIN.elyMontra, data: { kind: 'v3', pool: POOL.a, quote: W(99901), fee: 3000 } } },
  { name: 'v4 elysium native', chainId: 99901, pool: v4(COIN.elyMontra, keyOf(COIN.elyMontra, NATIVE, 10000, 200, zeroAddress), NATIVE, 'Uniswap v4 · 1%') },
  // PancakeSwap, Aerodrome.
  { name: 'pancake v2', chainId: 56, pool: { venueId: 'pancake-v2-bsc', id: POOL.a, label: 'PancakeSwap v2', token: COIN.bscCoin, data: { pair: POOL.a, stable: false } } },
  { name: 'pancake v2 via USDT', chainId: 56, pool: { venueId: 'pancake-v2-bsc', id: POOL.a, label: 'PancakeSwap v2 via USDT', token: COIN.bscCoin, data: { pair: POOL.a, stable: false, via: BSC.usdt } } },
  { name: 'pancake v3', chainId: 56, pool: { venueId: 'pancake-v3-bsc', id: POOL.b, label: 'PancakeSwap v3 · 1%', token: COIN.bscCoin, data: { pool: POOL.b, fee: 10000 } } },
  { name: 'pancake v3 via USD1', chainId: 56, pool: { venueId: 'pancake-v3-bsc', id: POOL.b, label: 'PancakeSwap v3 · 0.25% via USD1', token: COIN.bscCoin, data: { pool: POOL.b, fee: 2500, via: { stable: BSC.usd1, fee: 500 } } } },
  { name: 'pancake v3 eth', chainId: 1, pool: { venueId: 'pancake-v3-eth', id: POOL.c, label: 'PancakeSwap v3 · 1%', token: COIN.ethCoin, data: { pool: POOL.c, fee: 10000 } } },
  { name: 'pancake v3 base', chainId: 8453, pool: { venueId: 'pancake-v3-base', id: POOL.c, label: 'PancakeSwap v3 · 0.25%', token: COIN.baseCoin, data: { pool: POOL.c, fee: 2500 } } },
  { name: 'aerodrome volatile', chainId: 8453, pool: { venueId: 'aerodrome-base', id: POOL.a, label: 'Aerodrome · volatile', token: COIN.baseCoin, data: { pair: POOL.a, stable: false } } },
  { name: 'aerodrome stable via USDC', chainId: 8453, pool: { venueId: 'aerodrome-base', id: POOL.a, label: 'Aerodrome · stable via USDC', token: COIN.baseCoin, data: { pair: POOL.a, stable: true, via: BASE.usdc } } },
];

const DEADLINE = BigInt(NOW_S + 600);
const AMOUNTS = { buyIn: 12_345_678_901_234_567n, buyMin: 987_654_321_000_000_000_000n, sellIn: 4_200_000_000_000_000_000_000n, sellMin: 3_210_987_654_321n };

/** The validator's context, as the engine's validationContext builds it. */
function vctx(
  chainId: number,
  extra: Partial<ValidationContext> & { maxValue: bigint },
  pool?: PoolRef,
  over: { from?: Address; own?: Address[] } = {},
): { ctx: ValidationContext; json: unknown } {
  const chain = chainOf(chainId);
  const rules = new Map<string, ExtraContractRule>([...venueRules(chainId), ...launchpadRules(chainId)]);
  const v = pool ? venue(pool.venueId) : undefined;
  for (const [addr, rule] of (pool && v?.poolRules?.(ctxOf(chainId), pool)) || []) rules.set(addr.toLowerCase(), rule);
  if (chain.nestyRouter) rules.set(chain.nestyRouter.toLowerCase(), nestyRouterRule(chain.nestyRouter));
  const from = over.from ?? MADE_UP_WALLET;
  const own = over.own ?? [MADE_UP_WALLET, OTHER_WALLET];
  const ctx: ValidationContext = {
    chain,
    from,
    ownWallets: own,
    router: UNISWAP[chainId]?.universalRouter,
    extraContracts: rules,
    nowS: NOW_S,
    ...extra,
  };
  const json = { chainId, from, own, nowS: NOW_S, pool: pool ?? null, ...extra };
  return { ctx, json };
}

const verdicts: unknown[] = [];
function judge(name: string, chainId: number, tx: TxDraft, c: { ctx: ValidationContext; json: unknown }) {
  verdicts.push({ name, chainId, tx, ctx: c.json, result: validateTx(tx, c.ctx) });
}

const approveTx = (token: Address, spender: Address, amount: bigint): TxDraft => ({
  to: token,
  data: encodeFunctionData({ abi: erc20WriteAbi, functionName: 'approve', args: [spender, amount] }),
  value: 0n,
});

/** A Permit2 permit for a Universal Router sell, signed by the made-up key. */
async function urPermit(chainId: number, token: Address, amount: bigint): Promise<SellParams['permit']> {
  const permit = buildPermit(token, UNISWAP[chainId]?.universalRouter as Address, amount, 3, NOW_S);
  const signature = await account.signTypedData({ domain: permitDomain(chainId), types: PERMIT_TYPES, primaryType: 'PermitSingle', message: permit });
  return { permit, signature };
}

test('builds and verdicts', async () => {
  const builds: unknown[] = [];
  for (const c of POOLS) {
    const v = venue(c.pool.venueId);
    const ctx = ctxOf(c.chainId);
    const buy = { wallet: MADE_UP_WALLET, amountIn: AMOUNTS.buyIn, minOut: AMOUNTS.buyMin, deadline: DEADLINE };
    const sell = { wallet: MADE_UP_WALLET, amountIn: AMOUNTS.sellIn, minOut: AMOUNTS.sellMin, deadline: DEADLINE };
    const buyTx = v.buildBuy(ctx, c.pool, buy);
    const sellTx = v.buildSell(ctx, c.pool, sell);
    const approval = v.sellApproval(ctx, c.pool);
    const entry: Record<string, unknown> = { name: c.name, chainId: c.chainId, pool: c.pool, buy, sell, buyTx, sellTx, approval };
    if (approval.viaPermit2) {
      const permit = await urPermit(c.chainId, c.pool.token, AMOUNTS.sellIn);
      entry.sellPermit = { permit, tx: v.buildSell(ctx, c.pool, { ...sell, permit }) };
    }
    if (v.buildSellPrefunded) entry.prefunded = v.buildSellPrefunded(ctx, c.pool, { wallet: MADE_UP_WALLET, minOut: AMOUNTS.sellMin, deadline: DEADLINE });
    builds.push(entry);

    // Verdicts: the builds as built, then tampered copies.
    const funded = !!v.funding?.(ctx, c.pool);
    const maxValue = 10n ** 18n;
    const token = funded ? (v.funding?.(ctx, c.pool)?.asset as Address) : c.pool.token;
    void token;
    judge(`${c.name}: buy`, c.chainId, buyTx, vctx(c.chainId, { token: c.pool.token, maxValue }, c.pool));
    judge(`${c.name}: sell`, c.chainId, sellTx, vctx(c.chainId, { token: c.pool.token, maxValue: 0n, maxPermit: AMOUNTS.sellIn }, c.pool));
    if (entry.sellPermit) {
      const sp = entry.sellPermit as { tx: TxDraft };
      judge(`${c.name}: sell with permit`, c.chainId, sp.tx, vctx(c.chainId, { token: c.pool.token, maxValue: 0n, maxPermit: AMOUNTS.sellIn }, c.pool));
      judge(`${c.name}: permit above the sale`, c.chainId, sp.tx, vctx(c.chainId, { token: c.pool.token, maxValue: 0n, maxPermit: AMOUNTS.sellIn - 1n }, c.pool));
    }
    // To someone else, a different coin, no minimum, a value that does not match, a stranger signing.
    const toStranger = v.buildBuy(ctx, c.pool, { ...buy, wallet: STRANGER });
    judge(`${c.name}: buy for a stranger`, c.chainId, toStranger, vctx(c.chainId, { token: c.pool.token, maxValue }, c.pool));
    judge(`${c.name}: buy to my other wallet`, c.chainId, v.buildBuy(ctx, c.pool, { ...buy, wallet: OTHER_WALLET }), vctx(c.chainId, { token: c.pool.token, maxValue }, c.pool));
    judge(`${c.name}: sell for a stranger`, c.chainId, v.buildSell(ctx, c.pool, { ...sell, wallet: STRANGER }), vctx(c.chainId, { token: c.pool.token, maxValue: 0n, maxPermit: AMOUNTS.sellIn }, c.pool));
    judge(`${c.name}: buy without a minimum`, c.chainId, v.buildBuy(ctx, c.pool, { ...buy, minOut: 0n }), vctx(c.chainId, { token: c.pool.token, maxValue }, c.pool));
    judge(`${c.name}: sell without a minimum`, c.chainId, v.buildSell(ctx, c.pool, { ...sell, minOut: 0n }), vctx(c.chainId, { token: c.pool.token, maxValue: 0n, maxPermit: AMOUNTS.sellIn }, c.pool));
    judge(`${c.name}: buy of another coin`, c.chainId, buyTx, vctx(c.chainId, { token: STRANGER, maxValue }, c.pool));
    judge(`${c.name}: buy with more value`, c.chainId, { ...buyTx, value: buyTx.value + 1n }, vctx(c.chainId, { token: c.pool.token, maxValue }, c.pool));
    judge(`${c.name}: sell with value`, c.chainId, { ...sellTx, value: 1n }, vctx(c.chainId, { token: c.pool.token, maxValue }, c.pool));
    judge(`${c.name}: buy above the limit`, c.chainId, buyTx, vctx(c.chainId, { token: c.pool.token, maxValue: buyTx.value - 1n }, c.pool));
    judge(`${c.name}: signed by a stranger`, c.chainId, buyTx, vctx(c.chainId, { token: c.pool.token, maxValue }, c.pool, { from: STRANGER }));
    judge(`${c.name}: deadline far ahead`, c.chainId, v.buildBuy(ctx, c.pool, { ...buy, deadline: BigInt(NOW_S + 3601) }), vctx(c.chainId, { token: c.pool.token, maxValue }, c.pool));
    judge(`${c.name}: deadline gone`, c.chainId, v.buildSell(ctx, c.pool, { ...sell, deadline: BigInt(NOW_S - 1) }), vctx(c.chainId, { token: c.pool.token, maxValue: 0n, maxPermit: AMOUNTS.sellIn }, c.pool));
    judge(`${c.name}: unknown function`, c.chainId, { ...buyTx, data: `0xdeadbeef${buyTx.data.slice(10)}` as Hex }, vctx(c.chainId, { token: c.pool.token, maxValue }, c.pool));
    judge(`${c.name}: no pool rules`, c.chainId, buyTx, vctx(c.chainId, { token: c.pool.token, maxValue }));
    // Approvals for the sale.
    const held = AMOUNTS.sellIn;
    judge(`${c.name}: approve the spender`, c.chainId, approveTx(c.pool.token, approval.spender, held), vctx(c.chainId, { token: c.pool.token, maxApproval: held, maxValue: 0n }, c.pool));
    judge(`${c.name}: approve unlimited`, c.chainId, approveTx(c.pool.token, approval.spender, maxUint256), vctx(c.chainId, { token: c.pool.token, maxApproval: maxUint256, maxValue: 0n }, c.pool));
    judge(`${c.name}: approve above the bound`, c.chainId, approveTx(c.pool.token, approval.spender, held + 1n), vctx(c.chainId, { token: c.pool.token, maxApproval: held, maxValue: 0n }, c.pool));
    judge(`${c.name}: approve a stranger`, c.chainId, approveTx(c.pool.token, STRANGER, held), vctx(c.chainId, { token: c.pool.token, maxApproval: held, maxValue: 0n }, c.pool));
    judge(`${c.name}: approve without a bound`, c.chainId, approveTx(c.pool.token, approval.spender, held), vctx(c.chainId, { token: c.pool.token, maxValue: 0n }, c.pool));
  }

  // Token calls and plain sends.
  const tok = COIN.bscFour;
  const transfer = (to: Address, amount: bigint): TxDraft => ({ to: tok, data: encodeFunctionData({ abi: erc20WriteAbi, functionName: 'transfer', args: [to, amount] }), value: 0n });
  judge('transfer to my other wallet', 56, transfer(OTHER_WALLET, 5n), vctx(56, { token: tok, maxValue: 0n }));
  judge('transfer to a stranger', 56, transfer(STRANGER, 5n), vctx(56, { token: tok, maxValue: 0n }));
  judge('transfer to a typed payee', 56, transfer(STRANGER, 5n), vctx(56, { token: tok, maxValue: 0n, payee: STRANGER }));
  judge('token call with value', 56, { ...transfer(OTHER_WALLET, 5n), value: 1n }, vctx(56, { token: tok, maxValue: 10n }));
  judge('approve Permit2', 56, approveTx(tok, PERMIT2, 9n), vctx(56, { token: tok, maxApproval: 9n, maxValue: 0n }));
  judge('send to my other wallet', 56, { to: OTHER_WALLET, data: '0x', value: 7n }, vctx(56, { maxValue: 10n }));
  judge('call with data to my wallet', 56, { to: OTHER_WALLET, data: '0x1234', value: 0n }, vctx(56, { maxValue: 10n }));
  judge('call to an unknown contract', 56, { to: STRANGER, data: '0x1234', value: 0n }, vctx(56, { maxValue: 10n }));
  judge('send to a stranger', 1, { to: STRANGER, data: '0x', value: 7n }, vctx(1, { maxValue: 10n }));

  // Universal Router, tampered by hand.
  for (const chainId of [1, 56, 8453, 4663, 99901]) {
    const weth = W(chainId);
    const ur = UNISWAP[chainId]?.universalRouter as Address;
    const coin = COIN.ethCoin;
    const stable = chainOf(chainId).stablecoins[0]?.address as Address;
    const exec = (commands: Command[], deadline = DEADLINE, value = 10n ** 16n): TxDraft => ({ to: ur, data: encodeExecute(commands, deadline), value });
    const wrap: Command = { type: 'WRAP_ETH', recipient: ADDRESS_THIS, amount: 10n ** 16n };
    const v2 = (recipient: Address, path: Address[], amountOutMin = 5n, payerIsUser = false): Command => ({ type: 'V2_SWAP_EXACT_IN', recipient, amountIn: 10n ** 16n, amountOutMin, path, payerIsUser });
    const base = { token: coin, maxValue: 10n ** 18n };
    const cases: [string, TxDraft, Partial<ValidationContext>?][] = [
      ['ur buy', exec([wrap, v2(MADE_UP_WALLET, [weth, coin])])],
      ['ur buy to msg.sender', exec([wrap, v2(MSG_SENDER, [weth, coin])])],
      ['ur buy to a stranger', exec([wrap, v2(STRANGER, [weth, coin])])],
      ['ur buy without a minimum', exec([wrap, v2(MADE_UP_WALLET, [weth, coin], 0n)])],
      ['ur buy through a stranger coin', exec([wrap, v2(MADE_UP_WALLET, [weth, STRANGER, coin])])],
      ['ur buy through a stable', exec([wrap, v2(MADE_UP_WALLET, [weth, stable, coin])])],
      ['ur buy of another coin', exec([wrap, v2(MADE_UP_WALLET, [weth, stable])])],
      ['ur wraps more than its value', exec([{ ...wrap, amount: 10n ** 17n } as Command, v2(MADE_UP_WALLET, [weth, coin])])],
      ['ur wraps all', exec([{ ...wrap, amount: 1n << 255n } as Command, v2(MADE_UP_WALLET, [weth, coin])])],
      ['ur wrap for a stranger', exec([{ ...wrap, recipient: STRANGER } as Command, v2(MADE_UP_WALLET, [weth, coin])])],
      ['ur value unused', exec([v2(MADE_UP_WALLET, [coin, weth], 5n, true)])],
      ['ur nothing to me', exec([wrap, v2(ADDRESS_THIS, [weth, coin])])],
      ['ur stranded stable', exec([wrap, v2(ADDRESS_THIS, [weth, stable], 1n), v2(MADE_UP_WALLET, [coin, weth])])],
      ['ur stable used up', exec([wrap, v2(ADDRESS_THIS, [weth, stable], 1n), { type: 'V2_SWAP_EXACT_IN', recipient: MADE_UP_WALLET, amountIn: 1n << 255n, amountOutMin: 5n, path: [stable, coin], payerIsUser: false }])],
      ['ur sweep to a stranger', exec([wrap, v2(MADE_UP_WALLET, [weth, coin]), { type: 'SWEEP', token: coin, recipient: STRANGER, amountMin: 0n }])],
      ['ur unwrap to a stranger', exec([v2(ADDRESS_THIS, [coin, weth], 5n, true), { type: 'UNWRAP_WETH', recipient: STRANGER, amountMin: 5n }], DEADLINE, 0n)],
      ['ur sell', exec([v2(ADDRESS_THIS, [coin, weth], 5n, true), { type: 'UNWRAP_WETH', recipient: MADE_UP_WALLET, amountMin: 5n }], DEADLINE, 0n)],
      ['ur deadline far', exec([wrap, v2(MADE_UP_WALLET, [weth, coin])], BigInt(NOW_S + 1801))],
      ['ur deadline gone', exec([wrap, v2(MADE_UP_WALLET, [weth, coin])], BigInt(NOW_S - 1))],
      ['ur v3 buy', exec([wrap, { type: 'V3_SWAP_EXACT_IN', recipient: MADE_UP_WALLET, amountIn: 10n ** 16n, amountOutMin: 5n, path: [{ tokenIn: weth, fee: 3000, tokenOut: coin }], payerIsUser: false }])],
      ['ur v3 through a stranger', exec([wrap, { type: 'V3_SWAP_EXACT_IN', recipient: MADE_UP_WALLET, amountIn: 10n ** 16n, amountOutMin: 5n, path: [{ tokenIn: weth, fee: 3000, tokenOut: STRANGER }, { tokenIn: STRANGER, fee: 3000, tokenOut: coin }], payerIsUser: false }])],
      ['ur v4 take to a stranger', exec([{ type: 'V4_SWAP', actions: [
        { type: 'SWAP_EXACT_IN_SINGLE', poolKey: keyOf(coin, NATIVE, 3000, 60, zeroAddress), zeroForOne: true, amountIn: 10n ** 16n, amountOutMinimum: 5n, minHopPriceX36: 0n, hookData: '0x' },
        { type: 'SETTLE_ALL', currency: NATIVE, maxAmount: 10n ** 16n },
        { type: 'TAKE', currency: coin, recipient: STRANGER, amount: 0n },
      ] }])],
      ['ur v4 take all', exec([{ type: 'V4_SWAP', actions: [
        { type: 'SWAP_EXACT_IN_SINGLE', poolKey: keyOf(coin, NATIVE, 3000, 60, zeroAddress), zeroForOne: true, amountIn: 10n ** 16n, amountOutMinimum: 5n, minHopPriceX36: 0n, hookData: '0x' },
        { type: 'SETTLE', currency: NATIVE, amount: 10n ** 16n, payerIsUser: true },
        { type: 'TAKE_ALL', currency: coin, minAmount: 5n },
      ] }])],
      ['ur v4 without a minimum', exec([{ type: 'V4_SWAP', actions: [
        { type: 'SWAP_EXACT_IN_SINGLE', poolKey: keyOf(coin, NATIVE, 3000, 60, zeroAddress), zeroForOne: true, amountIn: 10n ** 16n, amountOutMinimum: 0n, minHopPriceX36: 0n, hookData: '0x' },
        { type: 'SETTLE_ALL', currency: NATIVE, maxAmount: 10n ** 16n },
        { type: 'TAKE', currency: coin, recipient: MADE_UP_WALLET, amount: 0n },
      ] }])],
      ['ur v4 pool of strangers', exec([{ type: 'V4_SWAP', actions: [
        { type: 'SWAP_EXACT_IN_SINGLE', poolKey: keyOf(STRANGER, NATIVE, 3000, 60, zeroAddress), zeroForOne: true, amountIn: 10n ** 16n, amountOutMinimum: 5n, minHopPriceX36: 0n, hookData: '0x' },
        { type: 'TAKE', currency: STRANGER, recipient: MADE_UP_WALLET, amount: 0n },
      ] }])],
      ['ur v4 pool without the coin', exec([{ type: 'V4_SWAP', actions: [
        { type: 'SWAP_EXACT_IN_SINGLE', poolKey: keyOf(stable, weth, 500, 10, zeroAddress), zeroForOne: true, amountIn: 10n ** 16n, amountOutMinimum: 5n, minHopPriceX36: 0n, hookData: '0x' },
        { type: 'TAKE', currency: stable, recipient: MADE_UP_WALLET, amount: 0n },
      ] }])],
    ];
    for (const [name, tx, extra] of cases) judge(`${name} (${chainId})`, chainId, tx, vctx(chainId, { ...base, ...extra }));
    // Calldata the decoder must refuse: an unknown command, the allow-revert flag, a mismatch.
    const good = exec([wrap, v2(MADE_UP_WALLET, [weth, coin])]);
    const cmdAt = good.data.indexOf('0b08');
    const raw = (swap: string) => ({ ...good, data: `${good.data.slice(0, cmdAt)}${swap}${good.data.slice(cmdAt + 4)}` as Hex });
    judge(`ur unknown command (${chainId})`, chainId, raw('0b01'), vctx(chainId, base));
    judge(`ur allow-revert (${chainId})`, chainId, raw('0b88'), vctx(chainId, base));
    judge(`ur not execute (${chainId})`, chainId, { ...good, data: '0x12345678' }, vctx(chainId, base));
  }

  // Permit2 permits before they are signed.
  const permits: unknown[] = [];
  for (const chainId of [1, 8453]) {
    const ur = UNISWAP[chainId]?.universalRouter as Address;
    const tokenP = COIN.baseCoin;
    const list: [string, PermitSingle, Partial<ValidationContext>][] = [
      ['permit ok', buildPermit(tokenP, ur, 100n, 0, NOW_S), { token: tokenP, maxPermit: 100n }],
      ['permit for another coin', buildPermit(STRANGER, ur, 100n, 0, NOW_S), { token: tokenP, maxPermit: 100n }],
      ['permit to a stranger', buildPermit(tokenP, STRANGER, 100n, 0, NOW_S), { token: tokenP, maxPermit: 100n }],
      ['permit above the sale', buildPermit(tokenP, ur, 101n, 0, NOW_S), { token: tokenP, maxPermit: 100n }],
      ['permit too long', buildPermit(tokenP, ur, 100n, 0, NOW_S + 7201), { token: tokenP, maxPermit: 100n }],
      ['permit without a bound', buildPermit(tokenP, ur, 100n, 0, NOW_S), { token: tokenP }],
    ];
    for (const [name, permit, extra] of list) {
      const c = vctx(chainId, { maxValue: 0n, ...extra });
      permits.push({ name, chainId, permit, ctx: c.json, result: validatePermit(permit, c.ctx) });
    }
  }

  // The Nesty Router: funded curve trades in one transaction.
  const router: unknown[] = [];
  const funded = POOLS.filter((c) => venue(c.pool.venueId).funding?.(ctxOf(c.chainId), c.pool));
  for (const c of funded) {
    const ctx = ctxOf(c.chainId);
    const v = venue(c.pool.venueId);
    const f = v.funding?.(ctx, c.pool);
    if (!f) continue;
    const chain = chainOf(c.chainId);
    const r: FundedRoute = { ctx, router: chain.nestyRouter as Address, wallet: MADE_UP_WALLET, venue: v, pool: c.pool, legVenue: venue(f.leg.venueId), leg: f.leg, asset: f.asset, deadline: DEADLINE };
    const buyCalls = routerBuyCalls({ ...r, amountIn: AMOUNTS.buyIn, assetMin: 777n, minOut: AMOUNTS.buyMin });
    const buyTx: TxDraft = { to: r.router, data: encodeRouterExecute({ calls: buyCalls, tokenOut: c.pool.token, minOut: AMOUNTS.buyMin, sweep: [f.asset, chain.wrappedNative], deadline: DEADLINE }), value: AMOUNTS.buyIn };
    const transfer = buildTransferPermit(c.pool.token, AMOUNTS.sellIn, NOW_S, (n) => Uint8Array.from({ length: n }, (_, i) => (i * 7 + 1) & 0xff));
    const signature = await account.signTypedData({ domain: permitDomain(c.chainId), types: TRANSFER_PERMIT_TYPES, primaryType: 'PermitTransferFrom', message: { ...transfer, spender: r.router } });
    const sellCalls = routerSellCalls({ ...r, assetMin: 555n, minOut: AMOUNTS.sellMin });
    const sellTx: TxDraft | null = sellCalls && { to: r.router, data: encodeRouterExecute({ calls: sellCalls, permit: { permit: transfer, signature }, tokenOut: ROUTER_NATIVE, minOut: AMOUNTS.sellMin, sweep: [c.pool.token, f.asset, chain.wrappedNative], deadline: DEADLINE }), value: 0n };
    router.push({ name: c.name, chainId: c.chainId, pool: c.pool, asset: f.asset, leg: f.leg, buyCalls, buyTx, transfer, signature, sellCalls, sellTx });
    const fctx = (extra: Partial<ValidationContext> & { maxValue: bigint }) => vctx(c.chainId, { token: c.pool.token, fundingAsset: f.asset, ...extra }, c.pool);
    judge(`router ${c.name}: buy`, c.chainId, buyTx, fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: buy without the asset`, c.chainId, buyTx, vctx(c.chainId, { token: c.pool.token, maxValue: 10n ** 18n }, c.pool));
    judge(`router ${c.name}: buy without value`, c.chainId, { ...buyTx, value: 0n }, fctx({ maxValue: 10n ** 18n }));
    const re = (p: { calls?: RouterCall[]; tokenOut?: Address; minOut?: bigint; sweep?: Address[]; deadline?: bigint }): TxDraft => ({
      ...buyTx,
      data: encodeRouterExecute({ calls: p.calls ?? buyCalls, tokenOut: p.tokenOut ?? c.pool.token, minOut: p.minOut ?? AMOUNTS.buyMin, sweep: p.sweep ?? [f.asset, chain.wrappedNative], deadline: p.deadline ?? DEADLINE }),
    });
    judge(`router ${c.name}: no minimum`, c.chainId, re({ minOut: 0n }), fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: asset left behind`, c.chainId, re({ sweep: [chain.wrappedNative] }), fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: sweeps a stranger`, c.chainId, re({ sweep: [f.asset, chain.wrappedNative, STRANGER] }), fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: pays out a stranger coin`, c.chainId, re({ tokenOut: STRANGER, sweep: [f.asset, chain.wrappedNative, c.pool.token] }), fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: deadline far`, c.chainId, re({ deadline: BigInt(NOW_S + 1801) }), fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: no steps`, c.chainId, re({ calls: [] }), fctx({ maxValue: 10n ** 18n }));
    const step0 = buyCalls[0] as RouterCall;
    const step1 = buyCalls[1] as RouterCall;
    judge(`router ${c.name}: a step to Permit2`, c.chainId, re({ calls: [step0, { ...step1, target: PERMIT2 }] }), fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: a step to the router`, c.chainId, re({ calls: [step0, { ...step1, target: r.router }] }), fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: a stranger may take`, c.chainId, re({ calls: [step0, { ...step1, spender: STRANGER }] }), fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: amount slot outside`, c.chainId, re({ calls: [step0, { ...step1, injectOffset: 2n }] }), fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: injects a stranger coin`, c.chainId, re({ calls: [step0, { ...step1, injectToken: STRANGER }] }), fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: steps spend more`, c.chainId, re({ calls: [{ ...step0, value: AMOUNTS.buyIn + 1n }, step1] }), fctx({ maxValue: 10n ** 18n }));
    judge(`router ${c.name}: a step to a stranger`, c.chainId, re({ calls: [step0, { ...step1, target: STRANGER }] }), fctx({ maxValue: 10n ** 18n }));
    if (sellTx) {
      judge(`router ${c.name}: sell`, c.chainId, sellTx, fctx({ maxValue: 0n, maxPermit: AMOUNTS.sellIn }));
      judge(`router ${c.name}: sell permit above the sale`, c.chainId, sellTx, fctx({ maxValue: 0n, maxPermit: AMOUNTS.sellIn - 1n }));
      judge(`router ${c.name}: sell with value`, c.chainId, { ...sellTx, value: 1n }, fctx({ maxValue: 10n }));
      judge(`router ${c.name}: sell to a stranger`, c.chainId, { to: r.router, data: encodeRouterExecute({ calls: routerSellCalls({ ...r, wallet: STRANGER, assetMin: 555n, minOut: AMOUNTS.sellMin }) as RouterCall[], permit: { permit: transfer, signature }, tokenOut: ROUTER_NATIVE, minOut: AMOUNTS.sellMin, sweep: [c.pool.token, f.asset, chain.wrappedNative], deadline: DEADLINE }), value: 0n }, fctx({ maxValue: 0n, maxPermit: AMOUNTS.sellIn }));
    }
    router.push({
      name: `${c.name}: transfer permits`,
      chainId: c.chainId,
      checks: [
        ['ok', checkTransferPermit(transfer, r.router, fctx({ maxValue: 0n, maxPermit: AMOUNTS.sellIn }).ctx)],
        ['stranger spender', checkTransferPermit(transfer, STRANGER, fctx({ maxValue: 0n, maxPermit: AMOUNTS.sellIn }).ctx)],
        ['above the sale', checkTransferPermit(transfer, r.router, fctx({ maxValue: 0n, maxPermit: 1n }).ctx)],
        ['other coin', checkTransferPermit({ ...transfer, permitted: { token: STRANGER, amount: 1n } }, r.router, fctx({ maxValue: 0n, maxPermit: AMOUNTS.sellIn }).ctx)],
        ['too long', checkTransferPermit({ ...transfer, deadline: BigInt(NOW_S + 1801) }, r.router, fctx({ maxValue: 0n, maxPermit: AMOUNTS.sellIn }).ctx)],
      ],
    });
  }

  // Arithmetic the engine uses on every trade.
  const gs = DEFAULT_GLOBAL_SETTINGS;
  const kinds: GasKind[] = ['swap', 'approve', 'transfer', 'tokenTransfer', 'deposit', 'routed'];
  const math = {
    minOutAfter: [
      [1_000_000n, 0, 1000], [1_000_000n, 300, 1000], [1n, 0, 1000], [10n ** 30n, 9_999, 5000], [10n ** 30n, 12_000, 1], [999n, 5, 5000],
    ].map(([out, tax, slip]) => ({ out, taxBps: tax, slippageBps: slip, min: minOutAfter(out as bigint, tax as number, slip as number) })),
    approvalTarget: [0n, 1n, 10n ** 24n].flatMap((held) => [true, false].map((unlimited) => ({ held, unlimited, bufferBps: gs.approvalBufferBps, target: approvalTarget({ ...gs, unlimitedApprovals: unlimited }, held) }))),
    priceImpact: [
      [10n ** 18n, 990n, 10n ** 16n, 10n], [10n ** 18n, 1000n, 10n ** 16n, 10n], [10n ** 18n, 2000n, 10n ** 16n, 10n], [0n, 1n, 1n, 1n], [5n, 1n, 1n, 0n],
    ].map(([a, o, ri, ro]) => ({ amountIn: a, out: o, refIn: ri, refOut: ro, bps: priceImpactBps(a as bigint, o as bigint, ri as bigint, ro as bigint) })),
    fees: [1, 56, 8453, 4663, 99901].flatMap((id) => {
      const chain = chainOf(id);
      return [0n, 1n, 7n, 3_000_000_000n, 25_000_000_000n].flatMap((baseFee) =>
        Object.entries(chain.gasPresets).map(([k, preset]) => ({ chainId: id, preset: k, ...preset, baseFee, ...computeFees(chain, baseFee, preset) })),
      );
    }),
    staticGas: [1, 56, 8453, 4663, 99901].flatMap((id) => kinds.map((kind) => ({ chainId: id, kind, gas: staticGas(chainOf(id), kind) }))),
    expectedFee: [1, 4663].flatMap((id) =>
      [true, false].flatMap((simulated) =>
        kinds.flatMap((kind) =>
          [null, 1_000_000_000n].map((base) => {
            const fees = { maxFeePerGas: 3_000_000_000n, maxPriorityFeePerGas: 1_000_000_000n };
            const gas = 400_000n;
            return { chainId: id, simulated, kind, base, gas, ...fees, fee: expectedFeeWei(chainOf(id), fees, base, gas, simulated, kind) };
          }),
        ),
      ),
    ),
    poolIds: POOLS.flatMap((c) => ((c.pool.data as { kind?: string })?.kind === 'v4' ? [{ key: (c.pool.data as { key: PoolKey }).key, id: poolIdOf((c.pool.data as { key: PoolKey }).key) }] : [])).concat([{ key: montraKey, id: poolIdOf(montraKey) }, { key: flethKey, id: poolIdOf(flethKey) }]),
  };

  write('builds.json', { note: 'Written by android/tools/vectors/builds.test.ts. Coins and pools are made up.', wallet: MADE_UP_WALLET, other: OTHER_WALLET, stranger: STRANGER, nowS: NOW_S, deadline: DEADLINE, amounts: AMOUNTS, builds });
  write('verdicts.json', { note: 'Written by android/tools/vectors/builds.test.ts.', verdicts, permits });
  write('router.json', { note: 'Written by android/tools/vectors/builds.test.ts. Signatures are by a MADE-UP key.', router });
  write('math.json', { note: 'Written by android/tools/vectors/builds.test.ts.', ...math });
});
