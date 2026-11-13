# Nesty for Android

A standalone, non-custodial trading app for Nesty, in Kotlin and Jetpack Compose.
The brief is `docs/android-brief.md` in the extension's folder.

This build (0.1.0) only reads: Discover, Token, Trades and holders, Signals,
Leaderboard, Wallet page, Tracker, Stats and Updates, with live data from
`https://nesty.trade/api/v1/` on BNB Chain, Base, Robinhood, Ethereum, Elysium
and Solana. It has no wallets and no keys; the buy buttons only say that buying
is not in this build yet.

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
