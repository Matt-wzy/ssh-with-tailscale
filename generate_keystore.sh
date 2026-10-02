#!/usr/bin/env bash
# Generate a release signing keystore and the matching keystore.properties.
#
# Both outputs are gitignored, so nothing here can leak into a commit by
# accident. Re-running overwrites: refuse to clobber an existing keystore unless
# --force is passed, because Android can never update an installed app with a
# differently-signed build -- losing the original .jks permanently strands
# everyone who already has the app.
set -euo pipefail

cd "$(dirname "$0")"

KEYSTORE=release.jks
ALIAS="${KEY_ALIAS:-sshwithtailscale}"
VALIDITY="${KEY_VALIDITY:-10000}"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

if [ -e "$KEYSTORE" ] && [ "$FORCE" -ne 1 ]; then
  echo "error: $KEYSTORE already exists." >&2
  echo "       Back it up first. If it is genuinely lost, re-run with --force and" >&2
  echo "       expect to uninstall the app before you can install the new build." >&2
  exit 1
fi

if [ -e keystore.properties ]; then
  echo "error: keystore.properties already exists; not overwriting it." >&2
  exit 1
fi

read -rsp "Keystore password (leave blank to generate one): " STORE_PASS
echo
if [ -z "$STORE_PASS" ]; then
  STORE_PASS="$(openssl rand -base64 24 | tr -d '\n/+=' | cut -c1-24)"
  echo "generated store password: $STORE_PASS"
fi

read -rsp "Key password (blank = same as store password): " KEY_PASS
echo
[ -z "$KEY_PASS" ] && KEY_PASS="$STORE_PASS"

cat > keystore.properties <<EOF
storeFile=$KEYSTORE
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF

keytool -genkeypair -v \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity "$VALIDITY" \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS"

chmod 600 keystore.properties "$KEYSTORE"

echo
echo ">> wrote $KEYSTORE and keystore.properties (both gitignored)"
echo ">> back up $KEYSTORE somewhere safe and keep it out of git"
