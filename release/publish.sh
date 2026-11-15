#!/usr/bin/env bash
# Publishes the APK build.sh made. It goes to the server's /var/www/nesty-app/ (served at
# https://nesty.trade/app/) and is checked there, then /var/lib/nesty-api/app.json names it:
# phones see it within 30 seconds. A publish is a deploy: run it once the user said yes to it.
#
#   bash android/release/publish.sh [notes.json]
#
# notes.json, optional, what the Update screen lists under "New in this version":
#   [{ "title": "Trade on Elysium", "text": "Montra coins, on their curve and after" }]
set -euo pipefail

HOST="${NESTY_HOST:-root@108.61.156.56}"
KEY="${NESTY_KEY:-$HOME/.ssh/id_ed25519}"
SITE=https://nesty.trade
cd "$(dirname "$0")"

REL=out/release.json
[ -f "$REL" ] || { echo "No android/release/$REL: run build.sh first." >&2; exit 1; }
NOTES="${1:-}"
[ -z "$NOTES" ] || [ -f "$NOTES" ] || { echo "No notes file at $NOTES" >&2; exit 1; }
read -r VERSION CODE SHA SIZE < <(node -e '
  const r = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
  console.log([r.version, r.code, r.sha256, r.size].join(" "));
' "$REL")
FILE="nesty-$VERSION.apk"
APK="out/$FILE"
URL="$SITE/app/$FILE"
[ "$(sha256sum "$APK" | cut -d' ' -f1)" = "$SHA" ] || { echo "$APK changed since build.sh made it." >&2; exit 1; }

# Only a version above the one phones are offered now.
LIVE="$(curl -fsS "$SITE/api/v1/app" 2>/dev/null || true)"
LIVE_CODE="$(node -e 'try { console.log(JSON.parse(process.argv[1]).code || 0) } catch { console.log(0) }' "$LIVE")"
[ "$CODE" -gt "$LIVE_CODE" ] || { echo "Version code $CODE is not above the live one ($LIVE_CODE)." >&2; exit 1; }

JSON="$(node -e '
  const [version, code, url, sha256, size, notesFile] = process.argv.slice(1);
  const notes = notesFile ? JSON.parse(require("fs").readFileSync(notesFile, "utf8")) : [];
  if (!Array.isArray(notes)) throw new Error("notes.json must be a list");
  console.log(JSON.stringify({ version, code: Number(code), url, sha256, size: Number(size), notes }, null, 2));
' "$VERSION" "$CODE" "$URL" "$SHA" "$SIZE" "$NOTES")"

echo "Uploading $FILE ($SIZE bytes) to $HOST"
ssh -i "$KEY" -o BatchMode=yes "$HOST" "set -e
  d=/var/www/nesty-app
  mkdir -p \$d
  if [ -e \$d/$FILE ]; then echo 'That version is already published.' >&2; exit 1; fi
  cat >\$d/$FILE.part
  echo '$SHA  '\$d/$FILE.part | sha256sum -c --quiet
  chmod 644 \$d/$FILE.part
  mv \$d/$FILE.part \$d/$FILE" <"$APK"

# Served as published, before anything names it.
[ "$(curl -fsS "$URL" | sha256sum | cut -d' ' -f1)" = "$SHA" ] || {
  echo "$URL does not serve the APK: is Caddy's /app/ route set up (website/api/README.md)?" >&2
  exit 1
}

printf '%s\n' "$JSON" | ssh -i "$KEY" -o BatchMode=yes "$HOST" "set -e
  d=/var/lib/nesty-api
  cat >\$d/app.json.new
  chmod 644 \$d/app.json.new
  mv \$d/app.json.new \$d/app.json"

echo "Published $VERSION (code $CODE). /api/v1/app shows it within 30 seconds:"
echo "  curl -s $SITE/api/v1/app"
