import { test } from 'vitest';
import { bytesToHex, hexToBytes } from '@noble/hashes/utils';
import {
  addressBytes,
  associatedTokenAddress,
  findProgramAddressWithBump,
  isOnCurve,
  seed,
  SYSTEM_PROGRAM,
  TOKEN_2022_PROGRAM,
  TOKEN_PROGRAM,
  USDC_MINT,
  WSOL_MINT,
} from '../../../src/background/solana/address';
import { explainError } from '../../../src/background/solana/errors';
import { publicKeyOf, signMessage } from '../../../src/background/solana/keys';
import {
  bondingCurveAddress,
  canonicalPoolAddress,
  PUMP_AMM_PROGRAM,
  PUMP_PROGRAM,
} from '../../../src/background/solana/pump';
import { metadataAddress } from '../../../src/background/solana/service';
import {
  closeAccount,
  createAtaIdempotent,
  setComputeUnitLimit,
  setComputeUnitPrice,
  syncNative,
  transfer,
  wsolAccount,
} from '../../../src/background/solana/spl';
import { compactU16, compileMessage, serializeTransaction, type Instruction } from '../../../src/background/solana/tx';
import { MADE_UP_BLOCKHASH, MADE_UP_SOL_SEED_HEX, OTHER_SOL, STRANGER_SOL, writeSol } from './shared';

/**
 * Offline Solana basics, written by the extension's own code: addresses (associated token
 * accounts, metadata, pump.fun's curve and pool, program addresses), instructions, compiled
 * messages and transactions, a signature by a MADE-UP seed over a made-up blockhash, and the
 * sentences transaction errors become.
 *
 *   pnpm vitest run --config android/tools/vectors/vitest.config.ts solana-basics
 */

const seedBytes = hexToBytes(MADE_UP_SOL_SEED_HEX);
const WALLET = publicKeyOf(seedBytes);

const ix = (i: Instruction) => ({
  programId: i.programId,
  keys: i.keys.map((k) => ({ pubkey: k.pubkey, isSigner: k.isSigner, isWritable: k.isWritable })),
  data: bytesToHex(i.data),
});

test('solana basics', () => {
  const PUMP_COIN = '5dWLmCFzZ8tRFEYpCSyWJw2e6JwFE4Rk7MqRuD2opump';
  const WIF = 'EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm';
  const mints = [WSOL_MINT, USDC_MINT, PUMP_COIN, WIF];
  const owners = [WALLET, OTHER_SOL, STRANGER_SOL];

  const atas = owners.flatMap((owner) =>
    mints.flatMap((mint) =>
      [TOKEN_PROGRAM, TOKEN_2022_PROGRAM].map((program) => ({
        owner,
        mint,
        program,
        ata: associatedTokenAddress(owner, mint, program),
      })),
    ),
  );
  const coins = mints.map((mint) => ({
    mint,
    metadata: metadataAddress(mint),
    curve: bondingCurveAddress(mint),
    poolSol: canonicalPoolAddress(mint, WSOL_MINT),
    poolUsdc: canonicalPoolAddress(mint, USDC_MINT),
  }));
  const pdas = [
    { seeds: [seed('global')], program: PUMP_PROGRAM },
    { seeds: [seed('bonding-curve'), addressBytes(PUMP_COIN)], program: PUMP_PROGRAM },
    { seeds: [seed('__event_authority')], program: PUMP_AMM_PROGRAM },
    { seeds: [seed('user_volume_accumulator'), addressBytes(WALLET)], program: PUMP_PROGRAM },
    { seeds: [Uint8Array.of(1, 2, 3), new Uint8Array(32).fill(9)], program: SYSTEM_PROGRAM },
  ].map((p) => {
    const [address, bump] = findProgramAddressWithBump(p.seeds, p.program);
    return { seeds: p.seeds.map((s) => bytesToHex(s)), program: p.program, address, bump };
  });
  const onCurve = [WALLET, OTHER_SOL, STRANGER_SOL, ...atas.slice(0, 4).map((a) => a.ata), coins[2]?.curve ?? '']
    .map((address) => ({ address, onCurve: isOnCurve(addressBytes(address)) }));

  const compact = [0, 1, 127, 128, 255, 256, 16_383, 16_384, 65_535].map((n) => ({ n, bytes: compactU16(n) }));

  const wsol = wsolAccount(WALLET);
  const named: Record<string, Instruction> = {
    unitLimit: setComputeUnitLimit(123_456),
    unitPrice: setComputeUnitPrice(4_053_498n),
    transfer: transfer(WALLET, STRANGER_SOL, 1_000_000n),
    openWsol: createAtaIdempotent(WALLET, WSOL_MINT, TOKEN_PROGRAM),
    openCoin2022: createAtaIdempotent(WALLET, PUMP_COIN, TOKEN_2022_PROGRAM),
    openForOther: createAtaIdempotent(OTHER_SOL, USDC_MINT, TOKEN_PROGRAM, WALLET),
    wrap: transfer(WALLET, wsol, 250_000_000n),
    sync: syncNative(wsol),
    close: closeAccount(wsol, WALLET),
  };
  const instructions = Object.entries(named).map(([name, i]) => ({ name, ...ix(i) }));

  const message = (payer: string, names: string[]) => {
    const ixs = names.map((n) => named[n] as Instruction);
    const m = compileMessage(payer, ixs, MADE_UP_BLOCKHASH);
    // MADE-UP seed, made-up blockhash: this signature can never land.
    const signature = payer === WALLET ? signMessage(m.bytes, seedBytes) : null;
    return {
      payer,
      blockhash: MADE_UP_BLOCKHASH,
      ixs: names,
      message: bytesToHex(m.bytes),
      accounts: m.accounts,
      numSigners: m.numSigners,
      unsigned: bytesToHex(serializeTransaction(m, [])),
      ...(signature && {
        signature: bytesToHex(signature),
        signed: bytesToHex(serializeTransaction(m, [signature])),
      }),
    };
  };
  const messages = [
    message(WALLET, ['unitLimit', 'unitPrice', 'transfer']),
    message(WALLET, ['unitLimit', 'unitPrice', 'openWsol', 'wrap', 'sync', 'openCoin2022', 'close']),
    message(WALLET, ['openForOther', 'transfer', 'transfer']),
    message(OTHER_SOL, ['openForOther']),
  ];

  const progs = (...p: string[]) => p.map((programId) => ({ programId, keys: [], data: new Uint8Array() }));
  const CB = 'ComputeBudget111111111111111111111111111111';
  const errorCases: { err: unknown; programs: string[] }[] = [
    { err: 'InsufficientFundsForFee', programs: [] },
    { err: 'BlockhashNotFound', programs: [] },
    { err: 'AccountNotFound', programs: [] },
    { err: 'InsufficientFunds', programs: [] },
    { err: 'SomethingNew', programs: [] },
    { err: 'not-a-kind!', programs: [] },
    { err: null, programs: [] },
    { err: {}, programs: [] },
    { err: { InsufficientFundsForRent: { account_index: 0 } }, programs: [] },
    { err: { InstructionError: [2, { Custom: 6002 }] }, programs: [CB, CB, PUMP_PROGRAM] },
    { err: { InstructionError: [2, { Custom: 6005 }] }, programs: [CB, CB, PUMP_PROGRAM] },
    { err: { InstructionError: [2, { Custom: 6040 }] }, programs: [CB, CB, PUMP_PROGRAM] },
    { err: { InstructionError: [2, { Custom: 6040 }] }, programs: [CB, CB, PUMP_AMM_PROGRAM] },
    { err: { InstructionError: [2, { Custom: 6094 }] }, programs: [CB, CB, PUMP_PROGRAM] },
    { err: { InstructionError: [2, { Custom: 6080 }] }, programs: [CB, CB, PUMP_AMM_PROGRAM] },
    { err: { InstructionError: [2, { Custom: 1 }] }, programs: [CB, CB, SYSTEM_PROGRAM] },
    { err: { InstructionError: [2, { Custom: 1 }] }, programs: [CB, CB, TOKEN_PROGRAM] },
    { err: { InstructionError: [2, { Custom: 1 }] }, programs: [CB, CB, PUMP_AMM_PROGRAM] },
    { err: { InstructionError: [2, { Custom: 1 }] }, programs: [CB, CB, TOKEN_2022_PROGRAM] },
    { err: { InstructionError: [2, { Custom: 42 }] }, programs: [CB, CB, WIF] },
    { err: { InstructionError: [9, { Custom: 6002 }] }, programs: [CB, CB, PUMP_PROGRAM] },
    { err: { InstructionError: [1, 'ProgramFailedToComplete'] }, programs: [CB, CB] },
    { err: { InstructionError: [1, 'ComputationalBudgetExceeded'] }, programs: [CB, CB] },
    { err: { InstructionError: [1, 'InvalidAccountData'] }, programs: [CB, CB] },
    { err: { InstructionError: [1, { BorshIoError: 'x' }] }, programs: [CB, CB] },
    { err: { DuplicateInstruction: 3 }, programs: [] },
  ];
  const errors = errorCases.map((c) => ({ ...c, text: explainError(c.err, progs(...c.programs)) }));

  writeSol('basics.json', {
    note: 'Written by the extension\'s Solana code. The seed is MADE-UP: never funded, never used on a chain; it signs over a made-up blockhash only.',
    seedHex: MADE_UP_SOL_SEED_HEX,
    wallet: WALLET,
    wsol,
    atas,
    coins,
    pdas,
    onCurve,
    compact,
    instructions,
    messages,
    errors,
  });
});
