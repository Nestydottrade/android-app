import { test, vi } from 'vitest';
import { bytesToHex, hexToBytes } from '@noble/hashes/utils';
import { base64 } from '@scure/base';
import {
  associatedTokenAddress,
  SYSTEM_PROGRAM,
  TOKEN_2022_PROGRAM,
  TOKEN_PROGRAM,
  WSOL_MINT,
  addressBytes,
} from '../../../src/background/solana/address';
import { dexModule } from '../../../src/background/solana/dex';
import { publicKeyOf } from '../../../src/background/solana/keys';
import { routeVariant } from '../../../src/background/solana/pump';
import {
  bestRoute,
  buyQuote,
  routePay,
  sellQuote,
  spotPrice,
  tradeUnits,
  validateTrade,
  type SolRoute,
} from '../../../src/background/solana/routes';
import { SolanaService, type SolSignRequest } from '../../../src/background/solana/service';
import { transfer } from '../../../src/background/solana/spl';
import type { Instruction } from '../../../src/background/solana/tx';
import { DEFAULT_GLOBAL_SETTINGS, DEFAULT_SOLANA_SETTINGS } from '../../../src/shared/settings/defaults';
import type { TradeTicket } from '../../../src/shared/panel';
import { MADE_UP_BLOCKHASH, MADE_UP_SOL_SEED_HEX, OTHER_SOL, STRANGER_SOL, writeSol } from './shared';

/**
 * Live, READ-ONLY: runs the extension's Solana engine (SolanaService) on real coins of every
 * venue it trades and records every account it reads. The Kotlin engine replays the record and
 * must find the same routes, the same quotes, and build the same transactions byte for byte.
 *
 * Nothing is signed or sent: the made-up wallet's signer only captures the message it is handed
 * and refuses, so each trade stops right there. The wallet's balances, the blockhash and the
 * simulation are the recorder's own made-up answers (never the network's); everything else is
 * read from chain through Nesty's /rpc/solana, ten accounts a call (the public node's cap),
 * then the public RPC.
 *
 *   VECTORS_LIVE=1 pnpm vitest run --config android/tools/vectors/vitest.config.ts solana-replay
 *
 * The world is held still while recording: an account read twice gets its first answer again.
 */

const PROXY = 'https://nesty.trade/api/v1/rpc/solana';
const PUBLIC = 'https://solana-rpc.publicnode.com';
const API = 'https://nesty.trade/api/v1/';

const WALLET = publicKeyOf(hexToBytes(MADE_UP_SOL_SEED_HEX));
const WALLET_ID = 'made-up';
/** The made-up wallet's SOL (it holds none on chain): 50 SOL, so no buy is short. */
const WALLET_LAMPORTS = 50_000_000_000;
const LAST_VALID = 412_000_150;
const HEIGHT = 412_000_000;
const SLOT = 430_000_000;
/** What the made-up simulation says every transaction uses. */
const UNITS = 87_654;
const BUY_SOL = ['0.01', '0.1', '1'];
const SELL_BPS = [2_500, 5_000, 10_000];

type Raw = { data: [string, 'base64']; owner: string; lamports: number; executable: boolean; rentEpoch?: number; space?: number } | null;

/** Every account served, by address: from chain, or made up for the wallet. */
const served = new Map<string, Raw>();
const fakes = new Map<string, Raw>();
const minimums = new Map<string, number>();
const refused: string[] = [];
const liveErrors: string[] = [];
let liveCalls = 0;
let fellBack = 0;
let simulations = 0;

class RpcError extends Error {
  constructor(
    message: string,
    readonly code: number,
  ) {
    super(message);
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function post(url: string, method: string, params: unknown): Promise<unknown> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
  });
  if (!res.ok) throw new Error(`http ${res.status}`);
  const j = (await res.json()) as { result?: unknown; error?: { code: number; message: string } };
  if (j.error) throw new RpcError(j.error.message, j.error.code);
  return j.result;
}

/** Nesty's /rpc first, as the app reads; the public RPC when it is busy or refuses. */
async function forward(method: string, params: unknown): Promise<unknown> {
  liveCalls++;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      return await post(PROXY, method, params);
    } catch (e) {
      if (e instanceof RpcError && e.code !== -32005 && e.code !== 429) throw e;
      await sleep(600 * (attempt + 1));
    }
  }
  fellBack++;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      return await post(PUBLIC, method, params);
    } catch (e) {
      if (attempt === 2) throw e;
      await sleep(1_000 * (attempt + 1));
    }
  }
  throw new Error('unreachable');
}

async function readLive(keys: string[]): Promise<void> {
  for (let i = 0; i < keys.length; i += 10) {
    const part = keys.slice(i, i + 10);
    try {
      const r = (await forward('getMultipleAccounts', [part, { encoding: 'base64', commitment: 'confirmed' }])) as {
        value: Raw[];
      };
      part.forEach((k, j) => {
        const a = r.value[j] ?? null;
        served.set(k, a && { data: a.data, owner: a.owner, lamports: a.lamports, executable: a.executable });
      });
    } catch (e) {
      liveErrors.push(`${String(e)} for ${part.join(',')}`);
      throw e;
    }
  }
}

async function answer(method: string, params: unknown[]): Promise<unknown> {
  switch (method) {
    case 'getMultipleAccounts': {
      const keys = params[0] as string[];
      const missing = [...new Set(keys.filter((k) => !fakes.has(k) && !served.has(k)))];
      if (missing.length > 0) await readLive(missing);
      return {
        context: { slot: SLOT },
        value: keys.map((k) => (fakes.has(k) ? (fakes.get(k) ?? null) : (served.get(k) ?? null))),
      };
    }
    case 'getBalance':
      if (params[0] !== WALLET) throw new RpcError(`refused by the recorder: getBalance of ${String(params[0])}`, -32601);
      return { context: { slot: SLOT }, value: WALLET_LAMPORTS };
    case 'getLatestBlockhash':
      return { context: { slot: SLOT }, value: { blockhash: MADE_UP_BLOCKHASH, lastValidBlockHeight: LAST_VALID } };
    case 'getBlockHeight':
      return HEIGHT;
    case 'simulateTransaction':
      simulations++;
      return { context: { slot: SLOT }, value: { err: null, logs: [], unitsConsumed: UNITS, accounts: null } };
    case 'getMinimumBalanceForRentExemption': {
      const key = JSON.stringify(params[0]);
      if (!minimums.has(key)) minimums.set(key, (await forward(method, params)) as number);
      return minimums.get(key);
    }
    default:
      refused.push(method);
      throw new RpcError(`refused by the recorder: ${method}`, -32601);
  }
}

const fetchFn = async (_url: string, init: RequestInit): Promise<Response> => {
  const req = JSON.parse(String(init.body)) as { id: number; method: string; params?: unknown[] };
  const json = (body: unknown) =>
    new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });
  try {
    return json({ jsonrpc: '2.0', id: req.id, result: await answer(req.method, req.params ?? []) });
  } catch (e) {
    return json({ jsonrpc: '2.0', id: req.id, error: { code: e instanceof RpcError ? e.code : -32601, message: String(e) } });
  }
};

/** A token account the made-up wallet holds (165 bytes: mint, owner, amount, initialized). */
function tokenAccount(mint: string, owner: string, amount: bigint, program: string): Raw {
  const d = new Uint8Array(165);
  d.set(addressBytes(mint), 0);
  d.set(addressBytes(owner), 32);
  new DataView(d.buffer).setBigUint64(64, amount, true);
  d[108] = 1;
  return { data: [base64.encode(d), 'base64'], owner: program, lamports: 2_039_280, executable: false };
}

fakes.set(WALLET, { data: ['', 'base64'], owner: SYSTEM_PROGRAM, lamports: WALLET_LAMPORTS, executable: false });

const tickets = new Map<string, TradeTicket>();
const captured: SolSignRequest[] = [];
const NOW = Date.now();

const gs = {
  ...DEFAULT_GLOBAL_SETTINGS,
  usePoolIndex: false,
  maxUsdPerTx: 1e9,
  maxUsdPerClick: 1e9,
  confirmAboveUsd: 1e9,
  selectedWalletIds: [WALLET_ID],
};
const ss = { ...DEFAULT_SOLANA_SETTINGS };
const makeService = () => new SolanaService({
  vault: {
    isUnlocked: async () => true,
    listAccounts: async () => [{ id: WALLET_ID, name: 'Made-up', hidden: false, primary: true, kind: 'imported', solana: WALLET }],
    solanaKeyFor: async () => {
      throw new Error('no keys in vectors');
    },
  } as never,
  settings: { get: async () => gs, getSolana: async () => ss } as never,
  local: { get: async () => undefined, set: async () => undefined, remove: async () => undefined },
  history: { list: async () => [] } as never,
  notify: (t: TradeTicket) => void tickets.set(t.id, t),
  fetchFn,
  sign: async (req: SolSignRequest) => {
    captured.push(req);
    throw new Error('Not signed: the vectors keep the message only.');
  },
  now: () => NOW,
});

const ix = (i: Instruction) => ({
  programId: i.programId,
  keys: i.keys.map((k) => ({ pubkey: k.pubkey, isSigner: k.isSigner, isWritable: k.isWritable })),
  data: bytesToHex(i.data),
});

function describe(r: SolRoute) {
  if (r.kind === 'pump') {
    return {
      kind: 'pump',
      variant: routeVariant(r.pump),
      pay: r.pump.pay.mint,
      hops: r.pump.hops.map((h) => ({ venue: h.kind, mint: h.mint, quote: h.quote.mint, ...(h.kind === 'pool' && { pool: h.poolAddress }) })),
    };
  }
  return { kind: 'dex', dex: r.dex, pool: r.pool, mint: r.mint, pay: r.quote.mint, label: dexModule(r).label(r) };
}

const terminal = (t: TradeTicket) => t.legs.every((l) => ['confirmed', 'failed', 'blocked', 'skipped'].includes(l.status));

/** A buy or sell through the service, up to the signature it never gets. */
async function run(start: () => Promise<TradeTicket>) {
  const before = captured.length;
  let ticket: TradeTicket;
  try {
    ticket = await start();
  } catch (e) {
    const err = e as { code?: string; message?: string };
    return { refused: { code: err.code ?? null, message: err.message ?? String(e) } };
  }
  for (let i = 0; i < 300; i++) {
    const t = tickets.get(ticket.id);
    if (t && terminal(t)) break;
    await sleep(50);
  }
  const t = tickets.get(ticket.id) ?? ticket;
  return {
    route: t.route ?? null,
    status: t.status,
    legs: t.legs.map((l) => ({ kind: l.kind, status: l.status, error: l.error ?? null, amountIn: l.amountIn ?? null })),
    signed: captured.slice(before).map((r) => ({
      blockhash: r.blockhash,
      intent: r.intent,
      instructions: r.instructions.map(ix),
      message: bytesToHex(r.message),
    })),
  };
}

interface Case {
  name: string;
  mint: string;
  /** Pools named for the coin, as the app passes them (the token page's pool and chart pool). */
  hints: string[];
}

async function getJson(url: string): Promise<unknown> {
  for (let attempt = 0; attempt < 3; attempt++) {
    const r = await fetch(url).catch(() => null);
    if (r?.ok) return r.json();
    await sleep(800 * (attempt + 1));
  }
  return null;
}

const isAddr = (s: unknown): s is string => typeof s === 'string' && /^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(s);

/** The pools the app names for a coin: its token page's pool and chart pool. */
async function hintsOf(mint: string): Promise<string[]> {
  const t = (await getJson(`${API}token/solana/${mint}`)) as { pool?: { address?: string }; chart?: { pool?: string } } | null;
  return [...new Set([t?.pool?.address, t?.chart?.pool].filter(isAddr))];
}

type Row = { address: string; symbol: string; pair?: string | null; pool?: string | null; venue?: string | null };

async function feedCases(): Promise<Case[]> {
  const feed = (await getJson(`${API}feed/solana?limit=50`)) as Record<string, Row[]> | null;
  const lane = (name: string) => (Array.isArray(feed?.[name]) ? (feed?.[name] as Row[]) : []);
  const fresh = [...lane('fresh'), ...lane('near')];
  const grad = lane('graduated');
  const pump = (r: Row) => r.address.endsWith('pump');
  const meteora = (r: Row) => (r.venue ?? '').toLowerCase().includes('meteora');
  const other = (r: Row) => !!r.pair && r.pair !== 'SOL' && r.pair !== 'USDC';
  const xStock = (r: Row) => !!r.pair && /^[A-Z]+x$/.test(r.pair);
  const wanted: [string, Row[], (r: Row) => boolean][] = [
    ['pump.fun curve, SOL', lane('fresh'), (r) => pump(r) && r.pair === 'SOL'],
    ['pump.fun curve near its end, SOL', lane('near'), (r) => pump(r) && r.pair === 'SOL'],
    ['pump.fun curve, USDC', fresh, (r) => pump(r) && r.pair === 'USDC'],
    ['pump.fun curve, priced in another coin', fresh, (r) => pump(r) && other(r) && !xStock(r)],
    ['pump.fun curve, priced in another coin (2)', fresh, (r) => pump(r) && other(r) && !xStock(r) && r.pair !== fresh.find((x) => pump(x) && other(x) && !xStock(x))?.pair],
    ['pump.fun curve, priced in an xStock', fresh, (r) => pump(r) && xStock(r)],
    ['PumpSwap, SOL', grad, (r) => pump(r) && r.pair === 'SOL'],
    ['PumpSwap, USDC', grad, (r) => pump(r) && r.pair === 'USDC'],
    ['PumpSwap, priced in a token', grad, (r) => pump(r) && other(r)],
    ['Meteora DBC', fresh, (r) => meteora(r)],
    ['Meteora DAMM v2 (graduated)', grad, (r) => meteora(r)],
  ];
  const out: Case[] = [];
  for (const [name, rows, pred] of wanted) {
    const r = rows.find(pred);
    if (!r) continue;
    // The token page's pools; the list row's own when the page has none yet (a coin seconds old).
    const named = await hintsOf(r.address);
    out.push({ name: `${name}: ${r.symbol}`, mint: r.address, hints: named.length > 0 ? named : [r.pool].filter(isAddr) });
    await sleep(200);
  }
  return out;
}

/** Coins on LaunchLab's curve now: the mints its newest transactions moved. */
async function launchLabMints(): Promise<string[]> {
  const LL = 'LanMV9sAd7wArD4vJFi2qDdfnVhFxYSUg6eADduJ3uj';
  const known = new Set([WSOL_MINT, 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v', 'USD1ttGY1N17NEEHLmELoaybftRBUSErhqYiQzvEmuB']);
  try {
    const sigs = (await post(PUBLIC, 'getSignaturesForAddress', [LL, { limit: 40 }])) as { signature: string; err: unknown }[];
    const found = new Map<string, number>();
    for (const s of sigs.filter((x) => !x.err).slice(0, 25)) {
      const tx = (await post(PUBLIC, 'getTransaction', [s.signature, { maxSupportedTransactionVersion: 0, encoding: 'json', commitment: 'confirmed' }])) as {
        meta?: { postTokenBalances?: { mint: string }[] };
      } | null;
      for (const b of tx?.meta?.postTokenBalances ?? []) if (!known.has(b.mint)) found.set(b.mint, (found.get(b.mint) ?? 0) + 1);
      await sleep(250);
    }
    return [...found.keys()].sort((a, b) => Number(b.endsWith('bonk')) - Number(a.endsWith('bonk'))).slice(0, 8);
  } catch {
    return [];
  }
}

const WIF = 'EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm';
const FIXED: Case[] = [
  {
    name: 'WIF: Raydium AMM v4, Meteora DLMM, Raydium CPMM, and Orca by its address',
    mint: WIF,
    hints: [
      'EP2ib6dYdEeqD8MfE2ezHCxX3kP3K2eLKkirfPm5eyMx',
      '8Ve9KtGNtLRxCQNAVfkHEP5GRZHjdj6BjB1RQFZewG6V',
      'FSYNx6cdFajxDJswDg38bJ2tU3AMZ4eo64LRhSouuCj3',
    ],
  },
  {
    name: 'WIF: Meteora DLMM priced in USDC',
    mint: WIF,
    hints: ['ADEjbFryutjfrJTpZfFPRMFhF7XBisPY63Awe79EVhe9'],
  },
  {
    name: 'Fartcoin: a pump.fun coin with deeper pools elsewhere',
    mint: '9BB6NFEcjBCtnNLFko2FqVQBq8HHM13kCyYcdQbgpump',
    hints: [
      'Bzc9NZfMqkXR6fz1DBph7BDf9BroyEf6pnzESP7v5iiw',
      '6wJ7W3oHj7ex6MVFp2o26NSof3aey7U8Brs8E371WCXA',
      'Tuy6gMupGQN7wCZ8rVP1EuLRYB132VSo9Smy4AJvQgn',
    ],
  },
  { name: 'pump.fun curve, USDC (fixed): KB The G', mint: 'Eues79ZnoMPM4jmNDnYi1zx3zH924QN5oRtMRWq1pump', hints: [] },
  { name: 'PumpSwap, USDC (fixed): BPUDGY', mint: 'G6ABuKSL97vvxShDYgH1ev4YrqLN6Hx1viw1o9ZKpump', hints: ['2WnfM8JDtesARk3vUop341iRCA71KhqWcD987DwHaf62'] },
  { name: 'Raydium AMM v4: LetsBONK', mint: 'CDBdbNqmrLu1PcgjrFG52yxg71QnFhBZcUE6PSFdbonk', hints: ['Hv1i8WrxEhL8djBuxhFGJxXTt4qEupywWKFSgGB5A7w6'] },
  { name: 'Raydium CPMM: Rayzor', mint: '21UaHGEGtQHs9TsJoonMwfVgH1cz8pQP6RxENbyJcook', hints: ['26Kc3wh5yC5LJDTAhXTtNdhTEee3cjLyertaQNQs3rRk'] },
  { name: 'LaunchLab candidate FgozUKaCY5qDC42CRgnQhzqqvQHRLkU5HNWG6gywNray', mint: 'FgozUKaCY5qDC42CRgnQhzqqvQHRLkU5HNWG6gywNray', hints: [] },
  { name: 'LaunchLab candidate Fx6yxLWY9siSbMSLfkTfmSjWraPyC8cDPFKVeq8uKe7Z', mint: 'Fx6yxLWY9siSbMSLfkTfmSjWraPyC8cDPFKVeq8uKe7Z', hints: [] },
  { name: 'Not a token', mint: OTHER_SOL, hints: [] },
];

async function record(c: Case) {
  const svc = makeService();
  // The app passes its hints newest first; the extension notes them one at a time, newest last.
  for (const h of [...c.hints].reverse()) svc.notePagePool(c.mint, h);
  const meta = await svc.tokenMeta(c.mint);
  if (!meta) {
    const market = await svc.market(c.mint);
    return { ...c, meta: null, market: { routes: market.routes.map(describe), idle: market.idle } };
  }
  // The wallet holds a hundred-thousandth of the supply, to size sells.
  const held = meta.supply / 100_000n || 1n;
  fakes.set(associatedTokenAddress(WALLET, c.mint, meta.tokenProgram), tokenAccount(c.mint, WALLET, held, meta.tokenProgram));
  const other = meta.tokenProgram === TOKEN_PROGRAM ? TOKEN_2022_PROGRAM : TOKEN_PROGRAM;
  fakes.set(associatedTokenAddress(WALLET, c.mint, other), null);

  const market = await svc.market(c.mint);
  const panel = await svc.panelQuotes({ mint: c.mint, buyAmounts: BUY_SOL, sellBps: SELL_BPS, walletIds: [WALLET_ID] });
  const buyIn = panel.buys.map((q) => q?.amountIn ?? null);
  const sellIn = SELL_BPS.map((bps) => (held * BigInt(bps)) / 10_000n);
  const raw = [10_000_000n, 100_000_000n, 1_000_000_000n];
  const routes = market.routes.map((r) => ({
    ...describe(r),
    spot: spotPrice(r),
    units: { buy: tradeUnits(r, 'buy', 100_000_000n), sell: tradeUnits(r, 'sell', sellIn[1] ?? 1n) },
    buys: raw.map((a) => buyQuote(r, a)),
    sells: sellIn.map((a) => sellQuote(r, a)),
  }));
  const funding = (market.funding ?? []).map((v) => ({
    dex: v.dex,
    pool: v.pool,
    label: dexModule(v).label(v),
    buys: raw.map((a) => buyQuote(v, a)),
  }));

  const buy = await run(() => svc.buy({ mint: c.mint, amount: '0.1', walletIds: [WALLET_ID], mode: 'each', confirmed: true }));
  const sell = await run(() => svc.sell({ mint: c.mint, bps: 5_000, walletIds: [WALLET_ID], confirmed: true }));

  // The validator, on the buy as built and changed: only a direct buy (one transaction).
  let verdicts: unknown[] = [];
  const first = 'signed' in buy ? buy.signed[0] : undefined;
  const best = bestRoute(market.routes, 'buy', 100_000_000n);
  if (first && !market.funding && best && routePay(best.route).mint === WSOL_MINT) {
    const ixs = first.instructions.map((i) => ({
      programId: i.programId,
      keys: i.keys,
      data: hexToBytes(i.data),
    })) as Instruction[];
    const ctx = {
      wallet: WALLET,
      mint: c.mint,
      route: best.route,
      side: 'buy' as const,
      maxSpend: 100_000_000n,
      maxPriorityLamports: 5_000_000n,
      payUnit: { symbol: 'SOL', decimals: 9 },
    };
    const plumbing = new Set(['ComputeBudget111111111111111111111111111111', SYSTEM_PROGRAM, TOKEN_PROGRAM, TOKEN_2022_PROGRAM, 'ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL']);
    const swapAt = ixs.findIndex((i) => !plumbing.has(i.programId));
    const changes: [string, Instruction[], Partial<typeof ctx>][] = [
      ['as built', ixs, {}],
      ['spend over the limit', ixs, { maxSpend: 99_999_999n }],
      ['priority over the limit', ixs, { maxPriorityLamports: 400_000n }],
      ['another wallet', ixs, { wallet: OTHER_SOL }],
      ['another coin', ixs, { mint: WIF === c.mint ? '9BB6NFEcjBCtnNLFko2FqVQBq8HHM13kCyYcdQbgpump' : WIF }],
      ['as a sell', ixs, { side: 'sell' as never, maxSpend: 0n }],
      ['an extra transfer', [...ixs, transfer(WALLET, STRANGER_SOL, 1_000n)], {}],
      ['the swap twice', swapAt >= 0 ? [...ixs.slice(0, swapAt + 1), ...ixs.slice(swapAt)] : ixs, {}],
      ['no unit limit', ixs.slice(1), {}],
    ];
    verdicts = changes.map(([name, list, change]) => {
      const v = validateTrade(list, { ...ctx, ...change });
      return {
        name,
        instructions: list.map(ix),
        ctx: { ...ctx, ...change, route: undefined, maxSpend: (change.maxSpend ?? ctx.maxSpend).toString(), maxPriorityLamports: (change.maxPriorityLamports ?? ctx.maxPriorityLamports).toString() },
        ok: v.ok,
        reason: v.actions.find((a) => !a.ok)?.reason ?? null,
        spend: v.spend,
        amountIn: v.amountIn,
        minOut: v.minOut,
      };
    });
  }

  return {
    ...c,
    meta: { symbol: meta.symbol, decimals: meta.decimals, tokenProgram: meta.tokenProgram, supply: meta.supply },
    held,
    market: { routes, idle: market.idle, funding },
    panel: {
      buys: panel.buys,
      buyIn,
      sells: panel.sells,
      sellIn,
      venue: panel.venue,
      idle: panel.idle,
      tokenUsd: panel.tokenUsd,
    },
    buy,
    sell,
    verdicts,
  };
}

test('solana replay', async () => {
  // Every random pick (pump.fun's fee account, the extra compute units) is the first one.
  vi.spyOn(Math, 'random').mockReturnValue(0);
  const cases = [...(await feedCases()), ...FIXED];
  for (const mint of await launchLabMints()) {
    if (!cases.some((c) => c.mint === mint)) cases.push({ name: `LaunchLab candidate ${mint}`, mint, hints: [] });
  }

  const out: unknown[] = [];
  for (const c of cases) {
    try {
      const r = await record(c);
      // LaunchLab candidates stay only when they trade there.
      if (c.name.startsWith('LaunchLab candidate')) {
        const routes = (r.market as { routes: { dex?: string }[] }).routes;
        if (!routes.some((x) => x.dex === 'launchlab')) continue;
        if (out.filter((x) => String((x as Case).name).startsWith('LaunchLab')).length >= 2) continue;
        (r as Case).name = `LaunchLab: ${(r as { meta?: { symbol?: string } }).meta?.symbol ?? c.mint}`;
      }
      out.push(r);
      console.log('recorded', c.name, JSON.stringify((r.market as { routes: unknown[] }).routes.map((x) => (x as { dex?: string; variant?: string }).dex ?? (x as { variant?: string }).variant)));
    } catch (e) {
      console.log('failed', c.name, String(e));
      out.push({ ...c, error: String(e) });
    }
  }

  const accounts: Record<string, Raw> = {};
  for (const [k, v] of served) accounts[k] = v;
  for (const [k, v] of fakes) accounts[k] = v;
  console.log(`live calls ${liveCalls}, fell back ${fellBack}, simulations ${simulations}, refused ${refused.join(',') || 'none'}, accounts ${Object.keys(accounts).length}`);
  if (liveErrors.length > 0) throw new Error(`live reads failed: ${liveErrors.join('; ')}`);

  writeSol('replay.json', {
    note: 'READ-ONLY record of the extension\'s Solana engine on real coins. The wallet is MADE-UP (never funded); its accounts, the blockhash and the simulation are made up; nothing was signed or sent.',
    nowMs: NOW,
    wallet: WALLET,
    other: OTHER_SOL,
    stranger: STRANGER_SOL,
    fake: {
      walletLamports: WALLET_LAMPORTS,
      blockhash: MADE_UP_BLOCKHASH,
      lastValidBlockHeight: LAST_VALID,
      blockHeight: HEIGHT,
      slot: SLOT,
      unitsConsumed: UNITS,
      madeUp: [...fakes.keys()],
    },
    prefs: {
      slippageBps: ss.slippageBps,
      priorityLamports: 500_000,
      maxGasUsd: gs.maxGasUsd,
    },
    solUsd: await makeService().price.usd(),
    minimums: Object.fromEntries(minimums),
    refused,
    cases: out,
    accounts,
  });
  vi.restoreAllMocks();
});
