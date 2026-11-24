#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
CREDENTIALS="$ROOT/.signing/credentials.env"
KEYSTORE="$ROOT/my-upload-key.jks"
REPO="Sjos0/FolioNot"

if [[ ! -f "$CREDENTIALS" || ! -f "$KEYSTORE" ]]; then
  echo "Signing files not found. Expected $CREDENTIALS and $KEYSTORE." >&2
  exit 1
fi

# Load locally protected credentials; never print their values.
set -a
# shellcheck disable=SC1090
source "$CREDENTIALS"
set +a

gh auth status >/dev/null
printf '%s' "$STORE_PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$KEY_PASSWORD" | gh secret set ANDROID_KEY_PASSWORD --repo "$REPO"
printf '%s' "$KEY_ALIAS" | gh secret set ANDROID_KEY_ALIAS --repo "$REPO"
base64 -w0 "$KEYSTORE" | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO"

echo "GitHub Actions signing secrets configured for $REPO (values were not displayed)."
echo "Verify names with: gh secret list --repo $REPO"
