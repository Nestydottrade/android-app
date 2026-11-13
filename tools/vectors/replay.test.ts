import { test } from 'vitest';
import {
  createPublicClient,
  custom,
  decodeFunctionData,
  decodeFunctionResult,
  encodeFunctionResult,
  parseAbi,
  parseEther,
  type Address,
  type Hex,
} from 'viem';
import { getChain, type ChainConfig } from '../../../src/shared/chains';
import { MULTICALL3, toViemChain } from '../../../src/background/chain/client';
import { launchpadVenues } from '../../../src/background/trade/launchpads';
import { legSupported } from '../../../src/background/trade/nesty-router';
import { Routing } from '../../../src/background/trade/routing';
import type { PoolRef, Venue, VenueCtx } from '../../../src/background/trade/types';
import { uniswapVenue } from '../../../src/background/trade/uniswap/venue';
import { extraVenues } from '../../../src/background/trade/venues';
import { write } from './shared';

/**
 * Live, READ-ONLY: runs the TypeScript engine's discovery and quotes on real coins of every
 * launchpad and records every chain read it makes (eth_call, eth_getCode, …), each Multicall3
 * sub-call on its own. The Kotlin engine replays the record and must find the same pools and
 * the same quotes. Nothing is signed or sent. Reads go to Nesty's /rpc, then the public RPCs.
 *
 *   VECTORS_LIVE=1 pnpm vitest run --config android/tools/vectors/vitest.config.ts replay
 *
 * The world is held still while recording: a sub-call read twice gets its first answer
 * again, so the record is one consistent snapshot.
 */

const VENUES: Venue[] = [uniswapVenue, ...extraVenues, ...launchpadVenues];
const routing = new Routing(VENUES);
const venue = (id: string) => VENUES.find((v) => v.id === id) as Venue;

const aggregate3 = parseAbi([
  'struct Call3 { address target; bool allowFailure; bytes callData; }',
  'struct Result { bool success; bytes returnData; }',
  'function aggregate3(Call3[] calls) payable returns (Result[] returnData)',
]);

interface SubCall {
  ok: boolean;
  ret: Hex;
}

/** Per chain: every sub-call by `to:data`, and other reads by `method:params`. */
const calls = new Map<number, Map<string, SubCall>>();
const other = new Map<number, Map<string, unknown>>();
let served = 0;
let fellBack = 0;

class RpcError extends Error {
  constructor(
    message: string,
    readonly code: number,
    readonly data?: Hex,
  ) {
    super(message);
  }
}

async function post(url: string, method: string, params: unknown): Promise<unknown> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
  });
  if (!res.ok) throw new Error(`http ${res.status}`);
  const j = (await res.json()) as { result?: unknown; error?: { code: number; message: string; data?: Hex } };
  if (j.error) throw new RpcError(j.error.message, j.error.code, j.error.data);
  return j.result;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Nesty's /rpc first, as the app reads; the chain's public RPCs when it is busy or down. */
async function forward(chain: ChainConfig, method: string, params: unknown): Promise<unknown> {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const r = await post(`https://nesty.trade/api/v1/rpc/${chain.id}`, method, params);
      served++;
      return r;
    } catch (e) {
      if (e instanceof RpcError) throw e;
      await sleep(500 * (attempt + 1));
    }
  }
  fellBack++;
  let last: unknown;
  for (const url of chain.rpcUrls) {
    try {
      return await post(url, method, params);
    } catch (e) {
      if (e instanceof RpcError) throw e;
      last = e;
    }
  }
  throw last;
}

function recorder(chain: ChainConfig) {
  const subs = calls.get(chain.id) ?? new Map<string, SubCall>();
  calls.set(chain.id, subs);
  const rest = other.get(chain.id) ?? new Map<string, unknown>();
  other.set(chain.id, rest);
  const key = (to: string, data: string) => `${to.toLowerCase()}:${data.toLowerCase()}`;

  return async ({ method, params }: { method: string; params?: unknown }): Promise<unknown> => {
    const p = (params ?? []) as unknown[];
    if (method === 'eth_call') {
      const call = p[0] as { to: Address; data?: Hex; input?: Hex };
      const data = (call.data ?? call.input ?? '0x') as Hex;
      if (call.to.toLowerCase() === MULTICALL3.toLowerCase() && data.startsWith('0x82ad56cb')) {
        const { args } = decodeFunctionData({ abi: aggregate3, data });
        const list = args[0] as readonly { target: Address; callData: Hex }[];
        const keys = list.map((c) => key(c.target, c.callData));
        if (!keys.every((k) => subs.has(k))) {
          const raw = (await forward(chain, method, p)) as Hex;
          const results = decodeFunctionResult({ abi: aggregate3, functionName: 'aggregate3', data: raw });
          results.forEach((r, i) => {
            const k = keys[i] as string;
            if (!subs.has(k)) subs.set(k, { ok: r.success, ret: r.returnData });
          });
        }
        return encodeFunctionResult({
          abi: aggregate3,
          functionName: 'aggregate3',
          result: keys.map((k) => {
            const s = subs.get(k) as SubCall;
            return { success: s.ok, returnData: s.ret };
          }),
        });
      }
      const k = key(call.to, data);
      if (!subs.has(k)) {
        try {
          subs.set(k, { ok: true, ret: (await forward(chain, method, p)) as Hex });
        } catch (e) {
          if (!(e instanceof RpcError)) throw e;
          subs.set(k, { ok: false, ret: (e.data ?? '0x') as Hex });
        }
      }
      const s = subs.get(k) as SubCall;
      if (!s.ok) throw new RpcError('execution reverted', 3, s.ret);
      return s.ret;
    }
    if (method === 'eth_chainId') return `0x${chain.id.toString(16)}`;
    const k = `${method}:${JSON.stringify(p)}`;
    if (!rest.has(k)) rest.set(k, await forward(chain, method, p));
    return rest.get(k);
  };
}

const clients = new Map<number, VenueCtx>();
function ctxOf(chainId: number): VenueCtx {
  const hit = clients.get(chainId);
  if (hit) return hit;
  const chain = getChain(chainId) as ChainConfig;
  const client = createPublicClient({
    chain: toViemChain(chain),
    transport: custom({ request: recorder(chain) }, { retryCount: 0 }),
    batch: { multicall: { wait: 0, batchSize: 8_192 } },
  });
  // No pool index and no aggregator: the app reads pools from the chain alone.
  const ctx: VenueCtx = { chain, client: client as never, now: Date.now };
  clients.set(chainId, ctx);
  return ctx;
}

/** Virtuals: the newest coin still on its curve (as tests/live/virtuals-flaunch finds it). */
async function liveVirtuals(): Promise<Address | null> {
  const ctx = ctxOf(8453);
  const bonding = '0x1a540088125d00dd3990f9da45ca0859af4d3b01' as Address;
  const abi = parseAbi(['function tokenInfos(uint256) view returns (address)']);
  const at = (i: bigint) =>
    ctx.client.readContract({ address: bonding, abi, functionName: 'tokenInfos', args: [i] });
  let lo = 0n;
  let hi = 1n << 20n;
  while (hi - lo > 1n) {
    const mid = (lo + hi) / 2n;
    if (await at(mid).then(() => true, () => false)) lo = mid;
    else hi = mid;
  }
  for (let i = lo; i > lo - 12n; i--) {
    const t = await at(i).catch(() => null);
    if (!t) continue;
    const pools = await venue('virtuals').discover(ctx, t).catch(() => []);
    if (pools.length > 0) return t;
  }
  return null;
}

/** [what it is, chain, coin]. Coins come and go; the record keeps what they were. */
const CASES: [string, number, Address][] = [
  // four.meme (BNB Chain): curves, a quote-asset curve, graduated.
  ['four.meme curve', 56, '0xD2a37Aee629a8b6D14971568A20d550843154444'],
  ['four.meme curve, near the end', 56, '0x025686Acaf9794B71F225A4B4740f9B4722A4444'],
  ['four.meme curve (ffff)', 56, '0x017CF12322969374961092a6257EA9D0908BFFff'],
  ['four.meme in USDT', 56, '0xAa7291fB6d5db0b0018c8974590EC65DcdbD4444'],
  ['four.meme in USD1', 56, '0xE951E321da31a7A067EB3c6f63d6F7c2CAe44444'],
  ['four.meme QQQB', 56, '0x30D597f1335bFF2eE010A04b0C0159fc6780FfFf'],
  ['four.meme graduated', 56, '0xe6B4c05F0F6F52c6b0e8f9721b8383Da62fdfFFF'],
  // Flap.
  ['Flap curve (BNB Chain)', 56, '0x08e476f73674FE9E44e672dFC82950Cf731B7777'],
  ['Flap curve, near the end (BNB Chain)', 56, '0x0e2Fc4fD936f379c7834d8516a53a134aDDb7777'],
  ['Flap curve (8888)', 56, '0x6614275ba65F85b11C47B8e4566ABa9f745f8888'],
  ['Flap graduated (BNB Chain)', 56, '0xE6C63609ec319B84D2BCB2Ca5c0E6b4Dbb9E7777'],
  ['Flap curve (Base)', 8453, '0x0aB3A64a64D4bB7a681348f29b7f106381747777'],
  ['Flap graduated (Base)', 8453, '0xCa28c31733E1e8B2893B51bb2fe51Bb442377777'],
  // Pons (Robinhood).
  ['Pons curve', 4663, '0xaDeBf278434C51771b88AE1788b979D29EAd1326'],
  ['Pons curve, near the end', 4663, '0x198CbA7BEEEdba0304f130f06Af8524eCc4B1a08'],
  ['Pons graduated (v4)', 4663, '0xa78557B829215D36C88303cE2F34448DdA9B46fA'],
  // Montra (Elysium).
  ['Montra', 99901, '0xa71b9f784951C88619358571Eeeb3978Ea9f5c08'],
  ['Montra, near the end', 99901, '0x3bf70f13ba92dc35A59e99d6303cfB91DEC415BE'],
  ['Montra graduated', 99901, '0x69893f4C8Ea2569Aa9801C21CEA0C19600D9666c'],
  ['Montra busy', 99901, '0x5dcfb191f0cdb853bac3a6a4385e160e779e377f'],
  ['Montra in KNTQ (left out)', 99901, '0x547a2a9c5af6e4372ead794e80eaf3f42dbbaaac'],
  // Uniswap v4 launches.
  ['Stockereum', 1, '0x840dc5d6995FD8fCdfC50f38719e5043855a2A15'],
  ['Stockereum graduated', 1, '0x5df882f8788071b67d0283F958CA3CDC17f1aE2B'],
  ['Stockereum (test sample)', 1, '0x5d7602693a60afc4823991be8b25a83c84df78c2'],
  ['Klik', 1, '0xa7fc99ecb15f4cc732f8446071df06bea6bbbfb1'],
  ['Clanker v4 (Base)', 8453, '0x13b628fF6Db92070C0FBad79523240E0f5DeFb07'],
  ['Bankr / Doppler (Base)', 8453, '0x4Ca71a516C8FFaed884f525943F748e7A7Be5Ba3'],
  ['Bankr / Doppler (Base, test sample)', 8453, '0xB9B2F3016b5bC5D95B90D9eD48A60f6b5a8aabA3'],
  ['Bankr / Doppler (Robinhood)', 4663, '0x100765a16f42636bd84ee1e86bc5107b84bcdba3'],
  ['Flaunch (via flETH)', 8453, '0xe2f9db0186b13668aec9fe0e15dbd13004ed8d6f'],
  ['Zora creator coin (in ZORA)', 8453, '0x2505A44EAf959cd7108a938832ce8b025cD8825e'],
  ['Zora content coin (in a creator coin)', 8453, '0x9235a04b1e2cdc076DB600a5E891546CB22C0C4e'],
  ['Virtuals graduated', 8453, '0x10c56f005a379f8eafc88ff5c3f40d30f0031ac9'],
  // Graduated coins and their DEXes.
  ['Uniswap v2/v3 (PEPE)', 1, '0x6982508145454Ce325dDbE47a25d4ec3d2311933'],
  ['PancakeSwap (CAKE)', 56, '0x0E09FaBB73Bd3Ade0a17ECC321fD13a19e81cE82'],
  ['PancakeSwap v2 sample', 56, '0xe766023f5a0fa6ce91b9f98D327771437DfcA077'],
  ['PancakeSwap v3 sample', 56, '0x6bdcCe4A559076e37755a78Ce0c06214E59e4444'],
  ['Aerodrome (AERO)', 8453, '0x940181a94A35A4569E4529A3CDfB74e38FD98631'],
  ['Uniswap via a stable (Base)', 8453, '0xcb585250f852C6c6bf90434AB21A00f02833a4af'],
  ['Uniswap (Base)', 8453, '0x2bdfFbD8bBaF20E511A73B8689312BFeC5098aE9'],
  ['Uniswap v3 (Robinhood)', 4663, '0x0339f5459FC690aC85F1782e15782A151b4A9E1b'],
  ['Uniswap v4 found only by the index (Robinhood)', 4663, '0xC9675fe679C7d4e1b94416F66D61c96127eb964a'],
  ['Uniswap (Robinhood)', 4663, '0xdF0992E440dD0be65BD8439b609d6D4366bf1CB5'],
  ['USDC (Elysium)', 99901, '0xb47c4C8F73F009CC0C0d3f92B388d13E293F0B65'],
];

interface Quoted {
  venueId: string;
  id: string;
  outs: (bigint | null)[] | null;
}

async function quoteAll(
  ctx: VenueCtx,
  pools: PoolRef[],
  side: 'buy' | 'sell',
  amounts: bigint[],
): Promise<Quoted[]> {
  return Promise.all(
    pools.map(async (p) => ({
      venueId: p.venueId,
      id: p.id,
      outs: await venue(p.venueId)
        .quote(ctx, p, side, amounts)
        .catch(() => null),
    })),
  );
}

/** Routing.pick: per amount, the pool with the highest output, the first one on a tie. */
function pick(pools: PoolRef[], quoted: Quoted[], offset: number, count: number) {
  return Array.from({ length: count }, (_, i) => {
    let best: { pool: number; out: bigint; refOut: bigint | null } | null = null;
    quoted.forEach((q, p) => {
      const out = q.outs?.[i + offset] ?? null;
      if (out !== null && out > 0n && (!best || out > best.out))
        best = { pool: p, out, refOut: offset ? (q.outs?.[0] ?? null) : null };
    });
    const b = best as { pool: number; out: bigint; refOut: bigint | null } | null;
    return b ? { venueId: pools[b.pool]?.venueId, id: pools[b.pool]?.id, out: b.out, refOut: b.refOut } : null;
  });
}

test('replay: discovery and quotes on live chains (read-only)', async () => {
  const cases: [string, number, Address][] = [...CASES];
  const virtuals = await liveVirtuals();
  if (virtuals) cases.push(['Virtuals curve (newest live)', 8453, virtuals]);

  const out: unknown[] = [];
  for (const [label, chainId, token] of cases) {
    const ctx = ctxOf(chainId);
    const chain = ctx.chain;
    const discovered = await Promise.all(
      VENUES.filter((v) => v.supports(chainId)).map(async (v) => ({
        venueId: v.id,
        pools: await v.discover(ctx, token).catch((e: unknown) => ({ error: String(e).slice(0, 200) })),
      })),
    );
    const pools = discovered.flatMap((d) => (Array.isArray(d.pools) ? d.pools : []));
    const buyIn = chain.quickBuyAmounts.map((a) => parseEther(a));
    const smallest = buyIn.reduce((m, a) => (a > 0n && (m === 0n || a < m) ? a : m), 0n);
    const buyRef = smallest / 100n || 1n;
    const buyAmounts = [buyRef, ...buyIn];
    const buys = await quoteAll(ctx, pools, 'buy', buyAmounts);
    const bestBuy = pick(pools, buys, 1, buyIn.length);
    // Sells of what the second quick amount would buy: a quarter, half and all of it.
    const got = bestBuy[1]?.out ?? bestBuy[0]?.out ?? null;
    const sellIn = got ? [got / 4n, got / 2n, got].filter((a) => a > 0n) : [];
    const sellRef = sellIn.length ? sellIn[0] / 100n || 1n : 0n;
    const sellAmounts = sellIn.length ? [sellRef, ...sellIn] : [];
    const sells = sellIn.length ? await quoteAll(ctx, pools, 'sell', sellAmounts) : [];
    const bestSell = sellIn.length ? pick(pools, sells, 1, sellIn.length) : [];
    // What the engine reads around a trade on these pools.
    const extras = await Promise.all(
      pools.map(async (p) => {
        const v = venue(p.venueId);
        const tax = v.venueTax ? await v.venueTax(ctx, p).catch(() => null) : undefined;
        const f = v.funding?.(ctx, p);
        let funding: unknown = null;
        if (f) {
          const legVenue = venue(f.leg.venueId);
          const legBuy = await legVenue.quote(ctx, f.leg, 'buy', buyIn).catch(() => null);
          const assetIn = (legBuy ?? []).map((a) => a ?? 0n).filter((a) => a > 0n);
          funding = {
            asset: f.asset,
            symbol: f.symbol,
            leg: f.leg,
            legSupported: legSupported(ctx, legVenue, f.leg),
            legBuy,
            assetIn,
            assetBuy: assetIn.length ? await f.quoteAsset(ctx, 'buy', assetIn).catch(() => null) : null,
            assetSell: sellIn.length ? await f.quoteAsset(ctx, 'sell', sellIn).catch(() => null) : null,
          };
        }
        return { venueId: p.venueId, id: p.id, tax, funding };
      }),
    );
    const idle = pools.length === 0 || bestBuy.every((b) => !b) ? await routing.whyNone(ctx, token) : null;
    // The engine's own pick, which the record above must agree with.
    const engine = await routing.best(ctx, token, 'buy', buyIn);
    const agree = engine.every(
      (r, i) => (r === null && bestBuy[i] === null) || (r !== null && r.pool.id === bestBuy[i]?.id && r.out === bestBuy[i]?.out),
    );
    out.push({
      label,
      chainId,
      token,
      discovered,
      buyAmounts,
      buys,
      bestBuy,
      sellAmounts,
      sells,
      bestSell,
      extras,
      idle,
      agree,
    });
    const labels = pools.map((p) => `${p.venueId}:${p.label}`).join(', ') || '-';
    console.log(`${label} [${chainId}] ${labels} | best ${bestBuy.map((b) => b?.id.slice(0, 12) ?? '-').join(',')} | idle ${idle ?? '-'} | agree ${agree}`);
  }

  const chains: Record<string, unknown> = {};
  for (const [id, subs] of calls) {
    chains[id] = {
      calls: Object.fromEntries([...subs.entries()].sort(([a], [b]) => (a < b ? -1 : 1))),
      other: Object.fromEntries(other.get(id) ?? []),
    };
  }
  write('replay.json', {
    note: 'Written by android/tools/vectors/replay.test.ts from live chains (read-only). Every read the TypeScript engine made, held still.',
    recordedAt: new Date().toISOString().slice(0, 10),
    chains,
    cases: out,
  });
  console.log(`reads through /rpc ${served}, through public RPCs ${fellBack}`);
});
