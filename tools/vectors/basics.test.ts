import { test } from 'vitest';
import {
  encodeAbiParameters,
  hashTypedData,
  keccak256,
  parseAbiParameters,
  type Hex,
} from 'viem';
import { privateKeyToAccount } from 'viem/accounts';
import { PERMIT_TYPES, buildPermit, permitDomain } from '../../../src/background/trade/permit2';
import {
  TRANSFER_PERMIT_TYPES,
  buildTransferPermit,
} from '../../../src/background/trade/nesty-router';
import { PERMIT2 } from '../../../src/background/trade/uniswap/addresses';
import { MADE_UP_KEY, MADE_UP_WALLET, NOW_S, write } from './shared';

/**
 * ABI encoding, signed EIP-1559 transactions and Permit2 signatures, as viem makes them.
 * Every signature here is by the MADE-UP key of shared.ts.
 */

const account = privateKeyToAccount(MADE_UP_KEY);
const ROUTER = '0xfdf24705a48f08558a6eed9B72d24cF3Bbce07e5';
const TOKEN = '0x5c952063c7fc8610FFDB798152D69F0B9550762b';

const bytes = (n: number, seed: number): Hex =>
  `0x${Array.from({ length: n }, (_, i) => ((i * 31 + seed) & 0xff).toString(16).padStart(2, '0')).join('')}`;

test('basics', async () => {
  if (account.address !== MADE_UP_WALLET) throw new Error('made-up key changed');

  // ABI: types and values in viem's own words, so Kotlin parses the same type strings.
  const abi = [
    { types: 'uint256', values: [0n] },
    { types: 'uint256', values: [2n ** 256n - 1n] },
    { types: 'int24', values: [-200] },
    { types: 'int24', values: [887272] },
    { types: 'uint24, int24', values: [0x800000, -60] },
    { types: 'address, bool, uint8', values: [TOKEN, true, 255] },
    { types: 'bytes', values: ['0x'] },
    { types: 'bytes', values: [bytes(33, 7)] },
    { types: 'string', values: ['Nesty ✓'] },
    { types: 'bytes32', values: [keccak256('0x01')] },
    { types: 'address[]', values: [[TOKEN, ROUTER, PERMIT2]] },
    { types: 'uint256[]', values: [[]] },
    { types: 'bytes[]', values: [[bytes(4, 1), '0x', bytes(70, 3)]] },
    {
      types: '(address a, uint256 b, bytes c), uint256',
      values: [{ a: TOKEN, b: 5n, c: bytes(3, 9) }, 7n],
    },
    {
      types:
        '(address target, uint256 value, bytes data, address injectToken, uint256 injectOffset, address spender)[]',
      values: [
        [
          {
            target: TOKEN,
            value: 1n,
            data: bytes(68, 2),
            injectToken: ROUTER,
            injectOffset: 36n,
            spender: PERMIT2,
          },
          {
            target: ROUTER,
            value: 0n,
            data: '0x',
            injectToken: TOKEN,
            injectOffset: 0n,
            spender: TOKEN,
          },
        ],
      ],
    },
    {
      types:
        '((address currency0, address currency1, uint24 fee, int24 tickSpacing, address hooks) key, bool zeroForOne, uint128 amountIn)',
      values: [
        {
          key: { currency0: '0x0000000000000000000000000000000000000000', currency1: TOKEN, fee: 0x800000, tickSpacing: 200, hooks: ROUTER },
          zeroForOne: true,
          amountIn: 10n ** 18n,
        },
      ],
    },
  ].map((c) => ({
    ...c,
    encoded: encodeAbiParameters(parseAbiParameters(c.types), c.values as never),
  }));

  // Transactions: every chain, short and long data, values and fees across their ranges.
  const txs = [
    { chainId: 56, nonce: 0, to: TOKEN, value: 0n, data: '0x', gas: 21_000n, maxFeePerGas: 1n, maxPriorityFeePerGas: 0n },
    { chainId: 1, nonce: 1, to: ROUTER, value: 10n ** 18n, data: bytes(4, 0), gas: 500_000n, maxFeePerGas: 30_000_000_000n, maxPriorityFeePerGas: 2_000_000_000n },
    { chainId: 8453, nonce: 127, to: TOKEN, value: 123456789n, data: bytes(55, 5), gas: 250_000n, maxFeePerGas: 10_000_000n, maxPriorityFeePerGas: 10_000_000n },
    { chainId: 4663, nonce: 128, to: ROUTER, value: 0n, data: bytes(56, 6), gas: 3_000_000n, maxFeePerGas: 150_000_000n, maxPriorityFeePerGas: 0n },
    { chainId: 99901, nonce: 65_535, to: TOKEN, value: 5n * 10n ** 17n, data: bytes(300, 8), gas: 1_200_000n, maxFeePerGas: 2_000_000_000n, maxPriorityFeePerGas: 50_000_000n },
    { chainId: 56, nonce: 1_000_000, to: ROUTER, value: 2n ** 96n, data: bytes(70_000, 4), gas: 6_000_000n, maxFeePerGas: 2n ** 64n, maxPriorityFeePerGas: 2n ** 40n },
  ] as const;
  const signed = [];
  for (const tx of txs) {
    const raw = await account.signTransaction({ type: 'eip1559', ...tx });
    signed.push({ ...tx, raw, hash: keccak256(raw) });
  }

  // Permit2: an allowance permit (Universal Router sells) and a one-time transfer (Nesty Router).
  const permits = [];
  for (const chainId of [1, 56, 8453, 4663]) {
    const msg = buildPermit(TOKEN, ROUTER, 123_456_789_000n + BigInt(chainId), chainId % 7, NOW_S);
    const typed = {
      domain: permitDomain(chainId),
      types: PERMIT_TYPES,
      primaryType: 'PermitSingle',
      message: msg,
    } as const;
    permits.push({
      kind: 'single',
      chainId,
      permit: msg,
      digest: hashTypedData(typed),
      signature: await account.signTypedData(typed),
    });
    const transfer = buildTransferPermit(TOKEN, 987_654_321n * BigInt(chainId), NOW_S, (n) =>
      Uint8Array.from({ length: n }, (_, i) => (i * 13 + chainId) & 0xff),
    );
    const typedTransfer = {
      domain: permitDomain(chainId),
      types: TRANSFER_PERMIT_TYPES,
      primaryType: 'PermitTransferFrom',
      message: { ...transfer, spender: ROUTER },
    } as const;
    permits.push({
      kind: 'transfer',
      chainId,
      spender: ROUTER,
      permit: transfer,
      digest: hashTypedData(typedTransfer),
      signature: await account.signTypedData(typedTransfer),
    });
  }

  write('basics.json', {
    note: 'Written by android/tools/vectors/basics.test.ts. Signatures are by a MADE-UP key.',
    wallet: MADE_UP_WALLET,
    abi,
    txs: signed,
    permits,
  });
});
