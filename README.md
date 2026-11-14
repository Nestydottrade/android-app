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
- `ui/`: one folder per screen, shared parts in `Components.kt`, figures in
  `Format.kt`, the coin checks in `Checks.kt`.
- `app/src/test/`: unit tests; `resources/fixtures/` holds real API answers
  saved on 2026-11-13.
