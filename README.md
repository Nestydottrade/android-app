# Nesty for Android

A standalone, non-custodial trading app for Nesty, in Kotlin and Jetpack Compose.
The brief is `docs/android-brief.md` in the extension's folder.

This build (0.1.0) reads, holds wallets and trades on the EVM chains. It reads
Discover, Token, Trades and holders, Signals, Leaderboard, Wallet page, Tracker,
Stats and Updates, with live data from `https://nesty.trade/api/v1/` on BNB
Chain, Base, Robinhood, Ethereum, Elysium and Solana, and buys and sells on all
six (below).

## Wallets and keys

- `keys/`: recovery phrases (BIP-39, English, 12 or 24 words, no passphrase),
  EVM keys on `m/44'/60'/0'/0/0`, Solana keys on `m/44'/501'/0'/0'` (SLIP-0010),
  EIP-55 and Base58. The paths and rules are the extension's, so a phrase gives
  the same wallets in both. `keyVectors.json` holds the port to that: it is
  written by the libraries the extension uses.
- `vault/Vault.kt`: every wallet has its own secret (a phrase, or one private
  key that covers its own side only), all under one password. The password goes
  through Argon2id (32 MiB, the iteration count measured on the phone) and
  AES-256-GCM seals with that key. The wallets' names and addresses are one
  sealed blob. Each secret is a sealed blob of its own, opened only for the
  moment it is used and wiped after. While unlocked, memory holds the derived
  key and the public part, never a secret.
- `vault/AndroidVault.kt`: the whole file is sealed once more by a key made in
  the Android Keystore, which never gives it out, and lives in the folder
  Android leaves out of backups. A copied file is useless on another phone.
- Unlock is by password. Five tries are free, then each failure doubles a wait
  from 30 seconds up to 15 minutes; the count is kept on disk.
- Fingerprint unlock (Security in the menu, Android 9 and later, strong
  biometrics only): turning it on asks for the password, then a finger seals
  the vault's derived key with a second Keystore key that works once per
  fingerprint (`vault/FingerprintKey.kt`, `ui/vault/Fingerprint.kt`). The lock
  asks for the finger as it opens, with the password beside it. Android drops
  that key when a fingerprint is added or the screen lock removed; the app then
  says so and the password turns it on again. No password is stored, and the
  phone limits fingerprint tries itself.
- It locks after 5, 15 or 60 minutes away from the app (the menu), on Lock now,
  and whenever Android ends the process.
- Screens: `ui/vault/` (the password, the lock, Wallets with create, import and
  back up), Portfolio with Deposit, and Security in the menu. A screen that
  shows a password, a phrase or a key keeps itself out of screenshots.
- One Balance is the wallet's own coin on each chain, in dollars, from
  `/balances/<address>`: the trading wallet every 30 seconds, the others when
  the Wallets screen opens. The server sees each address it is asked about.
- BouncyCastle (`bcprov-jdk18on`) does secp256k1, Keccak, Ed25519, PBKDF2 and
  Argon2id, through its own classes; it is never registered as a provider.

Tests and emulator runs use public test phrases only (the BIP-39 vectors and
the phrase development tools ship with). A wallet the app makes is never read
back or written anywhere.

A signal's wallets open in a sheet (`ui/signal/`): what each one bought, sold,
made and still holds in the coin, from the token page and from a signal's chip
on the Signals tab. It reads `signal` in the `/traders` answer, and where a
server has no such field it looks the wallets of `/signals` up in the answer's
two trader tables. EVM chains only: Solana has no signals.

Search asks `/search`, which lists launchpad coins after the listed ones (by
name, symbol or address; some rows come without a market cap or an image, and
`partial` says the listings could not be asked). As a backup, a pasted address
it does not list is asked for with `/token` on each chain it can be on
(`ui/discover/CoinSearch.kt`).

## Trading (EVM)

- `evm/` is the extension's EVM engine in Kotlin (`trading.ts`, `core.ts` and
  their venues): route, build, validate, simulate, sign, send, watch, one
  transaction at a time. Every launchpad the extension trades, on each of its
  chains: four.meme (BNB), Flap (BNB, Base, Robinhood), Pons on its curve and
  after it (Robinhood), Virtuals (Base), Montra (Elysium), Stockereum
  (Ethereum), Clanker (Ethereum, Base), Klik (Ethereum), Bankr/Doppler (Base,
  Robinhood), Flaunch (Base) and Zora (Base), with the Nesty Router for funded
  trades and Uniswap v2/v3/v4, PancakeSwap v2/v3 and Aerodrome pools.
- Nothing is signed unless the validator passed it, and a swap is simulated
  before it is signed (a sell right behind its own approval is the one
  exception, as in the extension). A buy may carry no more than the amount
  the user confirmed. Signing happens in the vault (`VaultSigner`): the key
  never leaves it.
- Reads go through Nesty's read-only RPC (`/rpc/<chain id>`): the server sees
  the trading wallet's balance, nonce, allowance, gas estimate and receipt
  reads. Sends go straight to each chain's own RPCs, built into the APK (the
  MEV-protected one on Ethereum and BNB Chain), so a sale never depends on
  Nesty's server.
- A landed swap is booked on the phone at once, then `/fill/<chain>/<tx>?wallet=`
  gives the server's reading of its receipt, which replaces it as the entry.
  `/balances/<address>?tokens=` names the coins of the wallet's positions, since
  an EVM chain cannot list what a wallet holds. Fills stay on the phone
  (`data/Positions.kt`).
- `data/Trading.kt` is what the screens call; `ui/trade/` has the one-tap buys
  with preset P1 (lists, token page), the buy sheet (any amount and preset), the
  sell bar (Initials, 25%, 50%, All), the position page and the Portfolio lists.
  A buy is paid from the chain's own balance: funds do not move between chains
  in this build.
- Left out of the extension's engine: the aggregator; the DEX Screener index
  (the server's v4 pool ids are used instead, each checked on chain); the tax
  probe (its call is not allowed through `/rpc`, so the minimums assume no tax
  and the simulation still refuses a trade a tax would break); funded trades in
  separate transactions (refused with "Update the app to trade here"); several
  wallets at once; speed-up and cancel; dollar limits per trade.
- Tests: `app/src/test/kotlin/trade/nesty/app/evm/` checks the port against
  vectors the extension's own code writes (`tools/vectors/`, run from the
  extension's folder with `npx vitest run --config android/tools/vectors/vitest.config.ts`)
  and against replays of recorded chain reads. Every signature in them is by a
  made-up key.

## Trading (Solana)

- `sol/` is the extension's Solana engine in Kotlin (`src/background/solana/`):
  pump.fun's curve and PumpSwap (with routes through other pump.fun coins),
  Raydium AMM v4, CPMM and LaunchLab, Meteora DBC, DAMM v2 and DLMM, and Orca
  Whirlpools. A coin priced in USDC or another token is bought with SOL in two
  transactions: SOL into that token on its best SOL pool, then the coin with
  exactly what arrived. Its sale pays back to SOL the same way.
- Before every signature the validator passes the exact instructions, compute
  budget included, and the transaction is simulated: on an error nothing is
  signed. A buy spends no more SOL than the user confirmed, and the priority fee
  stays under the max network fee. Signing is in the vault (`VaultSolSigner`).
- Reads, simulations and status checks go through `/rpc/solana`; sends go
  straight to the public Solana RPC. `getMultipleAccounts` reads ten accounts a
  call, the most the public node takes (a private RPC takes 100).
- Speed is the priority fee: Normal 0.0001, Fast 0.0005, Turbo 0.002 SOL.
- Left out of the extension's engine: the pool index (pools come from the
  coin's address, Orca's fixed-fee pools and the pools the server names), several
  wallets at once, and the dollar limits per transaction and per click. A coin
  priced in a token with no pool against SOL (an xStock, say) cannot be bought;
  its sale pays out in that token.
- Tests: `app/src/test/kotlin/trade/nesty/app/sol/` checks addresses,
  instructions, messages, a signature and error sentences against vectors the
  extension's own code writes (`tools/vectors/solana-basics.test.ts`), and 20
  recorded coins across every venue (`tools/vectors/solana-replay.test.ts`,
  which reads chain accounts, so it runs only with `VECTORS_LIVE=1`): the same
  routes, quotes, transactions byte for byte and validator verdicts as the
  extension. `SolEngineTest` runs buys and sells end to end on a made-up chain.
  Every signature is by a made-up key.

## One Balance and Withdraw

- One Balance is the wallet's own coin on each chain, in dollars, summed. The
  coins it holds are positions, not balance. Nothing moves by itself: every move
  starts from the user's one confirmation.
- Buying where the chain is short: the buy sheet takes the missing amount from
  the fullest other chain that covers it alone, and says how much is moved, what
  it costs ("Network and move fees", also counted in Cost to get in) and how long
  it takes. One Buy confirms both. The bridge pays the exact missing amount to
  the wallet's own address (exact output, capped at what was shown plus 1% and
  at what the source chain can give), then the buy goes out as usual. The move's
  cost joins the buy's cost basis. A one-tap buy the chain cannot pay opens the
  sheet with that amount.
- A sale pays out on its own chain. Max and All keep a little on each chain for
  network fees: the extension's bridge reserve, and on Solana the rent a wallet
  needs plus two fees.
- Moves are kept on the phone as they go (`move/Moves.kt`, never backed up) and
  followed again after the app was closed. A send that was being signed when the
  app closed is marked as not known, never as failed. Funds that landed for a buy
  while the app was closed, or more than 3 minutes after the confirmation, wait
  for the user: Portfolio shows them with Buy now (the token page opens its buy
  sheet) and Keep on <chain>. Moves that are not done are shown at the top of
  Portfolio and listed in Activity.
- Withdraw sends One Balance to an EVM or Solana address, pasted or typed (a
  mixed-case EVM address must carry a valid checksum). Taken from: all chains
  (the arriving chain first, then the fullest) or one chain only. Arrives on: the
  fullest chain the address works on, or the user's pick; each choice lists its
  fee and time. The arriving chain's share is a plain send, every other share a
  bridge paid to the receiver. The review shows the whole address in groups of
  four and asks for the fingerprint (the password when fingerprint unlock is off).
- The receiver is checked before the review and again right before signing: a
  token contract on an EVM chain; on Solana an address funds are lost at (a
  program, a mint, a token account) and a new address paid less than it needs to
  exist.
- Privacy: bridge quotes and status are asked by the phone, never through Nesty's
  server. Reads that name the receiver (its code, the send's simulation and
  receipt, the Solana account) go to the chain's own public RPCs, so Nesty's
  server never sees whom the user pays.
- A bridge quote passes the extension's checks before anything is signed
  (`move/Relay.kt`: the depository, the amount, the credited wallet, the payout
  chain and coin), and its deposit is validated and simulated like a trade. Each
  bridge is quoted again right before it is signed.
- Left out: QR scanning (it needs a library download, which is the user's call);
  moving funds out of Elysium (the bridge holds no HYPE there, and the
  extension's way out through USDC was never tried with real funds), so Elysium
  funds are only sent on Elysium; a buy's move from several chains at once (one
  chain covers it, or the sheet says how much it can); a buy sent just before the
  app closed is not booked as a fill after the restart (its hash is kept, so it
  is never asked again). Moving funds into Elysium works only if the bridge
  quotes it.
- Tests: `app/src/test/kotlin/trade/nesty/app/move/`. `RelayTest` checks quotes,
  deposits, status reads and the depository's validator rules against vectors
  the extension's own code writes (`tools/vectors/bridge.test.ts`) and a real
  exact-output answer. `PlansTest` checks the reserves, where a withdrawal comes
  from, a buy's shortfall and source, the cap, the cost basis, typed addresses,
  Solana receivers and moves kept across a restart.

## Build

The toolchain lives on `D:\Android` (JDK 21, Android SDK with platform 36,
Gradle home). `gradlew-local.cmd` points the Gradle wrapper at it.

```
gradlew-local.cmd assembleDebug        # app\build\outputs\apk\debug\app-debug.apk
gradlew-local.cmd testDebugUnitTest    # unit tests, on saved real API answers
```

Run it from this folder, or give the full path and `-p <this folder>`.

Versions are pinned in `gradle/libs.versions.toml`. Compose, OkHttp and Coil are
held at the last releases that build against compileSdk 36 (Compose BOM
2026.06.01, OkHttp 5.4.0, Coil 3.5.0); newer ones ask for compileSdk 37.

## Install on a phone

1. Copy `app-debug.apk` to the phone.
2. Open it and allow installing from that app when Android asks.

Or with USB debugging on: `adb install -r app\build\outputs\apk\debug\app-debug.apk`.

## Emulator

An Android 16 (API 36) Pixel 7 profile, `nesty36`, sits in `D:\Android\avd`:

```
set ANDROID_AVD_HOME=D:\Android\avd
D:\Android\sdk\emulator\emulator.exe -avd nesty36
```

## Layout

- `data/`: the API client (`NestyApi`, with the live stream), models, chains,
  local settings (quick buy, followed wallets, watchlist).
- `move/`: One Balance moves: the bridge client (`Relay.kt`), the arithmetic
  (`Plans` in `Moves.kt`), typed receivers, and the `Mover` that runs and keeps
  each move.
- `ui/`: one folder per screen, shared parts in `Components.kt`, figures in
  `Format.kt`, the coin checks in `Checks.kt`; Withdraw in `ui/withdraw/`.
- `app/src/test/`: unit tests; `resources/fixtures/` holds real API answers
  saved on 2026-11-13.
