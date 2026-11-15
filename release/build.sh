#!/usr/bin/env bash
# Builds Nesty's release APK, signed with the user's own key, and checks it.
#
#   bash android/release/build.sh <keystore> [alias]
#
# The keystore stays where the user keeps it: never in this repository, on the server or with
# Claude. Its password is asked here and handed to this one Gradle run through its environment.
# The APK and what publish.sh needs land in android/release/out/, which git leaves out.
set -euo pipefail

cd "$(dirname "$0")/.."
KS="${1:?Usage: bash android/release/build.sh <keystore> [alias]}"
ALIAS="${2:-nesty}"
SDK="${ANDROID_HOME:-D:/Android/sdk}"
BT="$SDK/build-tools/36.0.0"
# apksigner runs on Java: this PC's JDK, as gradlew-local.cmd uses it.
export JAVA_HOME="${JAVA_HOME:-D:/Android/jdk-21}"
PIN=release/signer.sha256

[ -f "$KS" ] || { echo "No keystore at $KS" >&2; exit 1; }
KS_ABS="$(cd "$(dirname "$KS")" && pwd)/$(basename "$KS")"
case "$KS_ABS" in "$(cd .. && pwd)"/*) echo "Keep the keystore outside the repository's folder." >&2; exit 1 ;; esac

read -rsp "Password of $(basename "$KS"): " PASS
echo
rm -f app/build/outputs/apk/release/app-release.apk
NESTY_KEYSTORE="$(cygpath -w "$KS_ABS")" NESTY_KEY_ALIAS="$ALIAS" NESTY_KEYSTORE_PASSWORD="$PASS" \
  ./gradlew-local.cmd testDebugUnitTest assembleRelease --console=plain
unset PASS

APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "Gradle made no signed APK: check the keystore, its alias and the password." >&2; exit 1; }

# The signer: never the debug key, and always the key of every earlier release.
CERTS="$("$BT/apksigner.bat" verify --print-certs "$(cygpath -w "$APK")" | tr -d '\r')"
if grep -q "CN=Android Debug" <<<"$CERTS"; then echo "This APK is signed with the debug key." >&2; exit 1; fi
SIGNER="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$CERTS")"
[ -n "$SIGNER" ] || { echo "apksigner read no signer." >&2; exit 1; }
if [ -f "$PIN" ]; then
  [ "$SIGNER" = "$(tr -d ' \r\n' <"$PIN")" ] || {
    echo "Signed with another key than the earlier releases ($PIN): every phone would refuse it." >&2
    exit 1
  }
else
  echo "$SIGNER" >"$PIN"
  echo "First release: its signer is pinned in android/$PIN. Commit that file."
fi

BADGE="$("$BT/aapt2.exe" dump badging "$(cygpath -w "$APK")" | tr -d '\r' | sed -n 1p)"
PKG="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGE")"
CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGE")"
VERSION="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGE")"
[ "$PKG" = trade.nesty.app ] || { echo "The APK is $PKG, not trade.nesty.app." >&2; exit 1; }

mkdir -p release/out
OUT="release/out/nesty-$VERSION.apk"
cp "$APK" "$OUT"
SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$OUT")"
node -e '
  const [file, version, code, sha256, size] = process.argv.slice(1);
  const json = JSON.stringify({ version, code: Number(code), sha256, size: Number(size) }, null, 2);
  require("fs").writeFileSync(file, json + "\n");
' release/out/release.json "$VERSION" "$CODE" "$SHA" "$SIZE"

echo "Built android/$OUT"
echo "  version $VERSION, code $CODE, $SIZE bytes"
echo "  sha256  $SHA"
echo "  signer  $SIGNER"
echo "Publishing it is a deploy: bash android/release/publish.sh [notes.json]"
