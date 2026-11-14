import { test } from 'vitest';
import { bytesToHex, hexToBytes } from '@noble/hashes/utils';
import { encodeFunctionData, erc20Abi, parseAbi, type Address, type Hex } from 'viem';
import { publicKeyOf } from '../../../src/background/solana/keys';
import {
  DEPOSIT_NATIVE,
  RELAY_SOLANA_PROGRAM,
  RELAY_SOLANA_STATE,
  RELAY_SOLANA_VAULT,
  relayDepositNative,
} from '../../../src/background/solana/relay';
import { setComputeUnitLimit, setComputeUnitPrice, transfer } from '../../../src/background/solana/spl';
import { solSendBlock } from '../../../src/background/solana/transfers';
import type { Instruction } from '../../../src/background/solana/tx';
import { validateRelayDeposit, validateSolanaTransfer } from '../../../src/background/solana/validator';
import { RELAY_DEPOSITORY, createRelayClient, relayRule, relayTokenRule } from '../../../src/background/trade/relay';
import { validateTx, type ValidationContext } from '../../../src/background/trade/validator';
import { getChain } from '../../../src/shared/chains';
import { MADE_UP_SOL_SEED_HEX, MADE_UP_WALLET, NOW_S, OTHER_SOL, OTHER_WALLET, STRANGER, STRANGER_SOL, write, writeSol } from './shared';

/**
 * The bridge's checks, written by the extension's own code (src/background/trade/relay.ts,
 * solana/relay.ts, the validators): quotes in the shape Relay answers, each with the verdict
 * the extension's client reaches on it (the request it sends, the schema, every check), the
 * depository's calldata, Relay's Solana deposit, the validator rules, and the rules a SOL send
 * must keep. Every address is made up. Nothing is asked of Relay or any chain.
 *
 *   pnpm vitest run --config android/tools/vectors/vitest.config.ts bridge
 */

const NATIVE = '0x0000000000000000000000000000000000000000';
const SOL_NATIVE = '11111111111111111111111111111111';
const SOLANA = 792703809;
/** The deposit's own id, inside the calldata. */
const DEPOSIT_ID: Hex = `0x${'5a'.repeat(32)}`;
/** Relay's id for the request: what the status path names. */
const REQUEST_ID: Hex = `0x${'17'.repeat(32)}`;
const OTHER_REQUEST: Hex = `0x${'18'.repeat(32)}`;
const ELY_USDC: Address = '0xb47c4C8F73F009CC0C0d3f92B388d13E293F0B65';
const SOL_WALLET = publicKeyOf(hexToBytes(MADE_UP_SOL_SEED_HEX));

const depositoryAbi = parseAbi(['function depositNative(address depositor, bytes32 id) payable']);
const depositoryTokenAbi = parseAbi([
  'function depositErc20(address depositor, address token, uint256 amount, bytes32 id)',
]);
const depositNative = (depositor: Address, id: Hex = DEPOSIT_ID) =>
  encodeFunctionData({ abi: depositoryAbi, functionName: 'depositNative', args: [depositor, id] });
const depositErc20 = (depositor: Address, token: Address, amount: bigint, id: Hex = DEPOSIT_ID) =>
  encodeFunctionData({
    abi: depositoryTokenAbi,
    functionName: 'depositErc20',
    args: [depositor, token, amount, id],
  });
const approve = (spender: Address, amount: bigint) =>
  encodeFunctionData({ abi: erc20Abi, functionName: 'approve', args: [spender, amount] });

const FEES = {
  gas: { amount: '213295771973', amountUsd: '0.000412' },
  relayer: { amount: '31818526589104', amountUsd: '0.061467' },
  relayerGas: { amount: '588364868758', amountUsd: '0.001137' },
  relayerService: { amount: '31230161720346', amountUsd: '0.060330' },
  app: { amount: '0', amountUsd: '0' },
};

type Json = Record<string, unknown>;
const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;

/** A quote as Relay answers one, around one transaction step. */
function quoteOf(o: {
  data: Json;
  recipient: string;
  toChainId: number;
  out: bigint;
  min: bigint;
  outAddress?: string;
  approval?: Json;
}): Json {
  const deposit = {
    id: 'deposit',
    kind: 'transaction',
    requestId: REQUEST_ID,
    items: [
      {
        status: 'incomplete',
        data: o.data,
        check: { endpoint: `/intents/status?requestId=${REQUEST_ID}`, method: 'GET' },
      },
    ],
  };
  const steps: Json[] = o.approval
    ? [{ id: 'approve', kind: 'transaction', requestId: REQUEST_ID, items: [{ status: 'incomplete', data: o.approval }] }, deposit]
    : [deposit];
  return {
    steps,
    fees: FEES,
    details: {
      operation: 'swap',
      sender: 'made-up',
      recipient: o.recipient,
      timeEstimate: 1,
      currencyOut: {
        currency: {
          chainId: o.toChainId,
          address: o.outAddress ?? (o.toChainId === SOLANA ? SOL_NATIVE : NATIVE),
          symbol: 'X',
          decimals: 18,
        },
        amount: o.out.toString(),
        minimumAmount: o.min.toString(),
      },
    },
  };
}

/** The extension's client answering `body` for `call`: the request it sent and its verdict. */
async function verdict(
  answer: { status?: number; body: unknown },
  call: (c: ReturnType<typeof createRelayClient>) => Promise<unknown>,
) {
  let sent: { path: string; method: string; body: unknown } | null = null;
  const client = createRelayClient((async (url: string, init?: RequestInit) => {
    sent = {
      path: String(url).replace('https://api.relay.link', ''),
      method: init?.method ?? 'GET',
      body: init?.body ? JSON.parse(String(init.body)) : null,
    };
    return new Response(JSON.stringify(answer.body), { status: answer.status ?? 200 });
  }) as typeof fetch);
  try {
    const r = await call(client);
    return { sent, ok: true, result: r };
  } catch (e) {
    return { sent, ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

/** A quote's result as JSON: Uint8Arrays as hex. */
const plain = (v: unknown) =>
  JSON.parse(
    JSON.stringify(v, (_k, x) =>
      typeof x === 'bigint' ? x.toString() : x instanceof Uint8Array ? bytesToHex(x) : x,
    ),
  ) as unknown;

test('EVM quotes, calldata and rules', async () => {
  const amount = 6_582_973_617_896_588n;
  const out = 20_000_000_000_000_000n;
  const floor = (out * 9950n) / 10000n;
  const ask = {
    user: MADE_UP_WALLET,
    recipient: OTHER_WALLET as string,
    fromChainId: 8453,
    toChainId: 56,
    amount,
  };
  const good = quoteOf({
    data: { from: MADE_UP_WALLET, to: RELAY_DEPOSITORY.toLowerCase(), data: depositNative(MADE_UP_WALLET), value: amount.toString(), chainId: 8453 },
    recipient: OTHER_WALLET.toLowerCase(),
    toChainId: 56,
    out,
    min: floor - 1n,
  });
  type Mut = (q: Json) => void;
  const step = (q: Json) => (q.steps as Json[])[(q.steps as Json[]).length - 1] as Json;
  const item = (q: Json) => ((step(q).items as Json[])[0] as Json);
  const data = (q: Json) => item(q).data as Json;
  const details = (q: Json) => q.details as Json;
  const currencyOut = (q: Json) => details(q).currencyOut as Json;
  const native: [string, Mut, Partial<typeof ask>?][] = [
    ['good', () => {}],
    ['recipient in another case', (q) => (details(q).recipient = OTHER_WALLET)],
    ['no fees', (q) => delete q.fees],
    ['only the relayer fee', (q) => (q.fees = { relayer: { amountUsd: '0.5' } })],
    ['fee not a number', (q) => (q.fees = { gas: { amountUsd: 'abc' } })],
    ['no time estimate', (q) => delete details(q).timeEstimate],
    ['minimum at the floor', (q) => (currencyOut(q).minimumAmount = floor.toString())],
    ['minimum two below the floor', (q) => (currencyOut(q).minimumAmount = (floor - 2n).toString())],
    ['minimum zero', (q) => (currencyOut(q).minimumAmount = '0')],
    ['minimum not a number', (q) => (currencyOut(q).minimumAmount = '1e18')],
    ['two steps', (q) => (q.steps = [step(q), step(q)])],
    ['no steps', (q) => (q.steps = [])],
    ['two items', (q) => (step(q).items = [item(q), item(q)])],
    ['step is a signature', (q) => (step(q).kind = 'signature')],
    ['another recipient', (q) => (details(q).recipient = STRANGER)],
    ['recipient not an address', (q) => (details(q).recipient = 'nobody')],
    ['pays out on another chain', (q) => ((currencyOut(q).currency as Json).chainId = 1)],
    ['pays out a token', (q) => ((currencyOut(q).currency as Json).address = ELY_USDC)],
    ['deposit on another chain', (q) => (data(q).chainId = 1)],
    ['another contract', (q) => (data(q).to = STRANGER)],
    ['value differs', (q) => (data(q).value = (amount + 1n).toString())],
    ['credits another wallet', (q) => (data(q).data = depositNative(STRANGER))],
    ['not a native deposit', (q) => (data(q).data = depositErc20(MADE_UP_WALLET, ELY_USDC, amount))],
    ['calldata not hex', (q) => (data(q).data = '0xzz')],
    ['no check, request id on the step', (q) => delete item(q).check],
    ['no check and no request id', (q) => {
      delete item(q).check;
      delete step(q).requestId;
    }],
    ['request id not bytes32', (q) => {
      delete item(q).check;
      step(q).requestId = '0x1234';
    }],
    ['status path of v3', (q) => (item(q).check = { endpoint: `/intents/status/v3?requestId=${REQUEST_ID}`, method: 'GET' })],
    ['status path elsewhere', (q) => (item(q).check = { endpoint: `https://evil.example/intents/status?requestId=${REQUEST_ID}`, method: 'GET' })],
    ['step names another request', (q) => (step(q).requestId = OTHER_REQUEST)],
    ['solana instructions', (q) => (item(q).data = { instructions: [] })],
    ['value missing', (q) => delete data(q).value],
    ['amount out missing', (q) => delete currencyOut(q).amount],
  ];
  const evm = [];
  for (const [name, mutate, over] of native) {
    const q = clone(good);
    mutate(q);
    const p = { ...ask, ...over };
    evm.push({ name, ask: plain(p), answer: q, ...plain(await verdict({ body: q }, (c) => c.quote(p))) as Json });
  }
  // To Solana: base58 recipients match exactly.
  const toSol = { ...ask, recipient: OTHER_SOL, toChainId: SOLANA };
  const solGood = quoteOf({
    data: { to: RELAY_DEPOSITORY, data: depositNative(MADE_UP_WALLET), value: amount.toString(), chainId: 8453 },
    recipient: OTHER_SOL,
    toChainId: SOLANA,
    out,
    min: out,
  });
  for (const [name, mutate] of [
    ['to solana', () => {}],
    ['to solana, recipient in another case', (q: Json) => (details(q).recipient = OTHER_SOL.toUpperCase())],
    ['to solana, pays an EVM native', (q: Json) => ((currencyOut(q).currency as Json).address = NATIVE)],
  ] as [string, Mut][]) {
    const q = clone(solGood);
    mutate(q);
    evm.push({ name, ask: plain(toSol), answer: q, ...plain(await verdict({ body: q }, (c) => c.quote(toSol))) as Json });
  }
  // Errors from the service itself.
  for (const [name, answer] of [
    ['service refuses with a message', { status: 400, body: { message: 'Amount is too low‮ to bridge\n' } }],
    ['service refuses without one', { status: 500, body: { error: 'x' } }],
    ['not a quote', { status: 200, body: { hello: 'world' } }],
  ] as [string, { status: number; body: unknown }][]) {
    evm.push({ name, ask: plain(ask), answer: answer.body, status: answer.status, ...plain(await verdict(answer, (c) => c.quote(ask))) as Json });
  }

  // In a token (bridgeVia), from Elysium.
  const tAmount = 12_345_678n;
  const tAsk = { user: MADE_UP_WALLET, recipient: MADE_UP_WALLET as string, fromChainId: 99901, toChainId: 8453, token: ELY_USDC, amount: tAmount };
  const tGood = quoteOf({
    data: { to: RELAY_DEPOSITORY, data: depositErc20(MADE_UP_WALLET, ELY_USDC, tAmount), value: '0', chainId: 99901 },
    approval: { to: ELY_USDC.toLowerCase(), data: approve(RELAY_DEPOSITORY, tAmount), value: '0', chainId: 99901 },
    recipient: MADE_UP_WALLET,
    toChainId: 8453,
    out,
    min: out,
  });
  const approval = (q: Json) => (((q.steps as Json[])[0] as Json).items as Json[])[0] as Json;
  const tokenCases: [string, Mut][] = [
    ['token, approve then deposit', () => {}],
    ['token, deposit only', (q) => (q.steps = [step(q)])],
    ['token, approve step not first', (q) => (q.steps = [step(q), (q.steps as Json[])[0]])],
    ['token, approve step named otherwise', (q) => (((q.steps as Json[])[0] as Json).id = 'permit')],
    ['token, approves another token', (q) => ((approval(q).data as Json).to = STRANGER)],
    ['token, approves another spender', (q) => ((approval(q).data as Json).data = approve(STRANGER, tAmount))],
    ['token, approves more', (q) => ((approval(q).data as Json).data = approve(RELAY_DEPOSITORY, tAmount + 1n))],
    ['token, approval not an approve', (q) => ((approval(q).data as Json).data = depositNative(MADE_UP_WALLET))],
    ['token, approval with value', (q) => ((approval(q).data as Json).value = '1')],
    ['token, approval on another chain', (q) => ((approval(q).data as Json).chainId = 1)],
    ['token, deposits another token', (q) => (data(q).data = depositErc20(MADE_UP_WALLET, STRANGER, tAmount))],
    ['token, deposits more', (q) => (data(q).data = depositErc20(MADE_UP_WALLET, ELY_USDC, tAmount + 1n))],
    ['token, credits another wallet', (q) => (data(q).data = depositErc20(STRANGER, ELY_USDC, tAmount))],
    ['token, deposit with value', (q) => (data(q).value = '1')],
    ['token, extra calldata', (q) => (data(q).data = `${depositErc20(MADE_UP_WALLET, ELY_USDC, tAmount)}00`)],
    ['token, native deposit', (q) => (data(q).data = depositNative(MADE_UP_WALLET))],
  ];
  const token = [];
  for (const [name, mutate] of tokenCases) {
    const q = clone(tGood);
    mutate(q);
    token.push({ name, ask: plain(tAsk), answer: q, ...plain(await verdict({ body: q }, (c) => c.quoteToken(tAsk))) as Json });
  }

  // Status: the path is checked before anything is asked.
  const status = [];
  for (const [name, path, toChainId, body] of [
    ['success with a payout', `/intents/status?requestId=${REQUEST_ID}`, 56, { status: 'success', txHashes: [`0x${'ab'.repeat(32)}`] }],
    ['pending', `/intents/status/v2?requestId=${REQUEST_ID}`, 56, { status: 'pending' }],
    ['no status', `/intents/status?requestId=${REQUEST_ID}`, 56, {}],
    ['payout on solana', `/intents/status?requestId=${REQUEST_ID}`, SOLANA, { status: 'success', txHashes: [`0x${'ab'.repeat(32)}`, '5'.repeat(88)] }],
    ['hashes not strings', `/intents/status?requestId=${REQUEST_ID}`, 56, { status: 'refund', txHashes: [1, null] }],
    ['bad path', `/quote?requestId=${REQUEST_ID}`, 56, { status: 'success' }],
  ] as [string, string, number, unknown][]) {
    status.push({ name, path, toChainId, answer: body, ...plain(await verdict({ body }, (c) => c.status(path, toChainId))) as Json });
  }

  // The validator: the depository's rules through validateTx.
  const base = getChain(8453)!;
  const ely = getChain(99901)!;
  const ctxOf = (chain: typeof base, rule: unknown, extra: Partial<ValidationContext> = {}): ValidationContext => ({
    chain,
    from: MADE_UP_WALLET,
    ownWallets: [MADE_UP_WALLET, OTHER_WALLET],
    extraContracts: new Map([[RELAY_DEPOSITORY.toLowerCase(), rule as never]]),
    maxValue: 10n ** 18n,
    nowS: NOW_S,
    ...extra,
  });
  const rules = [];
  const tx = (to: Address, data: Hex, value: bigint) => ({ to, data, value });
  for (const [name, t, ctx] of [
    ['native deposit', tx(RELAY_DEPOSITORY, depositNative(MADE_UP_WALLET), amount), ctxOf(base, relayRule)],
    ['native deposit for another own wallet', tx(RELAY_DEPOSITORY, depositNative(OTHER_WALLET), amount), ctxOf(base, relayRule)],
    ['native deposit for a stranger', tx(RELAY_DEPOSITORY, depositNative(STRANGER), amount), ctxOf(base, relayRule)],
    ['native deposit of nothing', tx(RELAY_DEPOSITORY, depositNative(MADE_UP_WALLET), 0n), ctxOf(base, relayRule)],
    ['native deposit above the limit', tx(RELAY_DEPOSITORY, depositNative(MADE_UP_WALLET), 2n * 10n ** 18n), ctxOf(base, relayRule)],
    ['token deposit to the native rule', tx(RELAY_DEPOSITORY, depositErc20(MADE_UP_WALLET, ELY_USDC, tAmount), 0n), ctxOf(base, relayRule)],
    ['token deposit', tx(RELAY_DEPOSITORY, depositErc20(MADE_UP_WALLET, ELY_USDC, tAmount), 0n), ctxOf(ely, relayTokenRule(ELY_USDC))],
    ['token deposit of another token', tx(RELAY_DEPOSITORY, depositErc20(MADE_UP_WALLET, STRANGER, tAmount), 0n), ctxOf(ely, relayTokenRule(ELY_USDC))],
    ['token deposit of nothing', tx(RELAY_DEPOSITORY, depositErc20(MADE_UP_WALLET, ELY_USDC, 0n), 0n), ctxOf(ely, relayTokenRule(ELY_USDC))],
    ['token deposit with value', tx(RELAY_DEPOSITORY, depositErc20(MADE_UP_WALLET, ELY_USDC, tAmount), 1n), ctxOf(ely, relayTokenRule(ELY_USDC))],
    ['token deposit for a stranger', tx(RELAY_DEPOSITORY, depositErc20(STRANGER, ELY_USDC, tAmount), 0n), ctxOf(ely, relayTokenRule(ELY_USDC))],
    ['native deposit to the token rule', tx(RELAY_DEPOSITORY, depositNative(MADE_UP_WALLET), amount), ctxOf(ely, relayTokenRule(ELY_USDC))],
    ['approve the depository', tx(ELY_USDC, approve(RELAY_DEPOSITORY, tAmount), 0n), ctxOf(ely, relayTokenRule(ELY_USDC), { token: ELY_USDC, maxApproval: tAmount, maxValue: 0n })],
    ['approve the depository for more', tx(ELY_USDC, approve(RELAY_DEPOSITORY, tAmount + 1n), 0n), ctxOf(ely, relayTokenRule(ELY_USDC), { token: ELY_USDC, maxApproval: tAmount, maxValue: 0n })],
    ['approve the depository under the native rule', tx(ELY_USDC, approve(RELAY_DEPOSITORY, tAmount), 0n), ctxOf(ely, relayRule, { token: ELY_USDC, maxApproval: tAmount, maxValue: 0n })],
    ['send to the payee', tx(STRANGER, '0x', 5n), ctxOf(base, relayRule, { payee: STRANGER })],
    ['send to a stranger', tx(STRANGER, '0x', 5n), ctxOf(base, relayRule)],
    ['send to the payee with data', tx(STRANGER, '0x12', 5n), ctxOf(base, relayRule, { payee: STRANGER })],
    ['send to the payee above the limit', tx(STRANGER, '0x', 2n * 10n ** 18n), ctxOf(base, relayRule, { payee: STRANGER })],
  ] as [string, ReturnType<typeof tx>, ValidationContext][]) {
    const r = validateTx(t, ctx);
    rules.push({
      name,
      chainId: ctx.chain.id,
      rule: ctx.extraContracts?.get(RELAY_DEPOSITORY.toLowerCase()) === relayRule ? 'native' : 'token',
      tx: plain(t),
      token: ctx.token ?? null,
      maxApproval: ctx.maxApproval?.toString() ?? null,
      maxValue: ctx.maxValue.toString(),
      payee: ctx.payee ?? null,
      ok: r.ok,
      actions: r.actions,
    });
  }

  write('bridge.json', {
    depository: RELAY_DEPOSITORY,
    wallet: MADE_UP_WALLET,
    other: OTHER_WALLET,
    stranger: STRANGER,
    depositId: DEPOSIT_ID,
    calldata: {
      depositNative: depositNative(MADE_UP_WALLET),
      depositErc20: depositErc20(MADE_UP_WALLET, ELY_USDC, tAmount),
    },
    evm,
    token,
    status,
    rules,
  });
});

test('Solana quotes, deposit, validators and send rules', async () => {
  const amount = 123_456_789n;
  const id = hexToBytes('5a'.repeat(32));
  const out = 40_000_000_000_000_000n;
  const dep = relayDepositNative(SOL_WALLET, amount, id);
  const ixJson = (i: Instruction) => ({
    programId: i.programId,
    keys: i.keys.map((k) => ({ pubkey: k.pubkey, isSigner: k.isSigner, isWritable: k.isWritable })),
    data: bytesToHex(i.data),
  });
  const budget = [setComputeUnitLimit(20_000), setComputeUnitPrice(25_000_000n)];
  const ask = { user: SOL_WALLET, recipient: MADE_UP_WALLET as Address, toChainId: 8453, amount };
  const good = quoteOf({
    data: { instructions: [...budget.map(ixJson), ixJson(dep)] },
    recipient: MADE_UP_WALLET.toLowerCase(),
    toChainId: 8453,
    out,
    min: out,
  });
  type Mut = (q: Json) => void;
  const ixs = (q: Json) =>
    ((((q.steps as Json[])[0] as Json).items as Json[])[0] as Json).data as { instructions: Json[] };
  const last = (q: Json) => ixs(q).instructions[ixs(q).instructions.length - 1] as Json;
  const cases: [string, Mut][] = [
    ['good', () => {}],
    ['without a compute budget', (q) => (ixs(q).instructions = [last(q)])],
    ['two deposits', (q) => ixs(q).instructions.push(clone(last(q)))],
    ['another program', (q) => (last(q).programId = STRANGER_SOL)],
    ['data too short', (q) => (last(q).data = (last(q).data as string).slice(0, 90))],
    ['another instruction', (q) => (last(q).data = `00${(last(q).data as string).slice(2)}`)],
    ['amount differs', (q) => (last(q).data = bytesToHex(relayDepositNative(SOL_WALLET, amount + 1n, id).data))],
    ['data with 0x', (q) => (last(q).data = `0x${last(q).data as string}`)],
    ['data not hex', (q) => (last(q).data = 'zz'.repeat(48))],
    ['credited to a stranger', (q) => (((last(q).keys as Json[])[2] as Json).pubkey = STRANGER_SOL)],
    ['vault differs', (q) => (((last(q).keys as Json[])[3] as Json).pubkey = STRANGER_SOL)],
    ['an account made writable', (q) => (((last(q).keys as Json[])[4] as Json).isWritable = true)],
    ['an EVM transaction', (q) => ((((q.steps as Json[])[0] as Json).items as Json[])[0] = { data: { to: RELAY_DEPOSITORY, data: '0x', value: '0', chainId: 8453 } })],
  ];
  const quotes = [];
  for (const [name, mutate] of cases) {
    const q = clone(good);
    mutate(q);
    quotes.push({ name, ask: plain(ask), answer: q, ...plain(await verdict({ body: q }, (c) => c.quoteFromSolana(ask))) as Json });
  }

  // The deposit validator, on the exact instructions a transaction holds.
  const ctx = { wallet: SOL_WALLET, amount, id, maxSpend: 10n ** 9n, maxPriorityLamports: 1_000_000n };
  const deposits = [];
  for (const [name, list, over] of [
    ['deposit with its budget', [...budget, dep], {}],
    ['deposit without a budget', [dep], {}],
    ['two deposits', [...budget, dep, dep], {}],
    ['no deposit', budget, {}],
    ['deposit of another amount', [...budget, relayDepositNative(SOL_WALLET, amount + 1n, id)], {}],
    ['deposit with another id', [...budget, relayDepositNative(SOL_WALLET, amount, new Uint8Array(32))], {}],
    ['deposit above the limit', [...budget, dep], { maxSpend: amount - 1n }],
    ['priority above the max', [setComputeUnitLimit(20_000), setComputeUnitPrice(10n ** 12n), dep], {}],
    ['a transfer beside it', [...budget, dep, transfer(SOL_WALLET, STRANGER_SOL, 1n)], {}],
    ['another signer', [...budget, { ...dep, keys: dep.keys.map((k, i) => (i === 1 ? { ...k, pubkey: STRANGER_SOL } : k)) }], {}],
  ] as [string, Instruction[], Partial<typeof ctx>][]) {
    const c = { ...ctx, ...over };
    const r = validateRelayDeposit(list, c);
    deposits.push({
      name,
      instructions: list.map(ixJson),
      amount: c.amount.toString(),
      id: bytesToHex(c.id),
      maxSpend: c.maxSpend.toString(),
      maxPriorityLamports: c.maxPriorityLamports.toString(),
      ok: r.ok,
      actions: r.actions,
    });
  }

  // A SOL send, as Withdraw makes one.
  const send = transfer(SOL_WALLET, STRANGER_SOL, amount);
  const tctx = { wallet: SOL_WALLET, to: STRANGER_SOL, asset: null, amount, maxPriorityLamports: 1_000_000n };
  const transfers = [];
  for (const [name, list, over] of [
    ['send with its budget', [setComputeUnitLimit(1_000), setComputeUnitPrice(100_000_000n), send], {}],
    ['send of another amount', [transfer(SOL_WALLET, STRANGER_SOL, amount + 1n)], {}],
    ['send to another address', [transfer(SOL_WALLET, OTHER_SOL, amount)], {}],
    ['send to itself', [transfer(SOL_WALLET, SOL_WALLET, amount)], { to: SOL_WALLET }],
    ['two sends', [send, send], {}],
    ['a deposit instead', [dep], {}],
    ['priority above the max', [setComputeUnitLimit(1_000), setComputeUnitPrice(10n ** 13n), send], {}],
  ] as [string, Instruction[], Partial<typeof tctx>][]) {
    const c = { ...tctx, ...over };
    const r = validateSolanaTransfer(list, c);
    transfers.push({
      name,
      instructions: list.map(ixJson),
      to: c.to,
      amount: c.amount.toString(),
      maxPriorityLamports: c.maxPriorityLamports.toString(),
      ok: r.ok,
      actions: r.actions,
    });
  }

  // What a wallet holding `lamports` may send, Solana's rent rule.
  const min = 890_880n;
  const fee = 105_000n;
  const blocks = [];
  for (const [lamports, value] of [
    [10_000_000n, 1_000_000n],
    [10_000_000n, 10_000_000n],
    [10_000_000n, 10_000_000n - fee],
    [10_000_000n, 10_000_000n - fee - 1n],
    [10_000_000n, 10_000_000n - fee - min],
    [10_000_000n, 10_000_000n - fee - min + 1n],
    [500_000n, 500_000n - fee],
    [min + fee, min],
  ] as [bigint, bigint][]) {
    blocks.push({ lamports: lamports.toString(), value: value.toString(), fee: fee.toString(), minBalance: min.toString(), block: solSendBlock(lamports, value, fee, min) });
  }

  writeSol('bridge.json', {
    program: RELAY_SOLANA_PROGRAM,
    state: RELAY_SOLANA_STATE,
    vault: RELAY_SOLANA_VAULT,
    discriminator: bytesToHex(Uint8Array.from(DEPOSIT_NATIVE)),
    wallet: SOL_WALLET,
    deposit: { amount: amount.toString(), id: bytesToHex(id), instruction: ixJson(dep) },
    quotes,
    deposits,
    transfers,
    blocks,
  });
});
