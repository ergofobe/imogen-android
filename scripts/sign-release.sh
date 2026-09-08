#!/usr/bin/env bash
# Sign the APK CI built for a tag, and attach it to that tag's release.
#
# Run this yourself, in your own terminal: apksigner prompts for the keystore password,
# and the whole point of doing it here rather than in a workflow is that the release key
# stays on this machine. Nothing in .github/ can produce a signed build.
#
#   scripts/sign-release.sh v0.2.3
#
# The keystore defaults to ~/.android/imogen-release.jks; override with IMOGEN_KEYSTORE.
set -euo pipefail

tag=${1:-}
if [ -z "$tag" ]; then
  echo "usage: scripts/sign-release.sh vX.Y.Z" >&2
  exit 2
fi

keystore=${IMOGEN_KEYSTORE:-$HOME/.android/imogen-release.jks}
if [ ! -f "$keystore" ]; then
  echo "error: no keystore at $keystore" >&2
  echo "It must be the same key every release — Android identifies the app by its" >&2
  echo "signing certificate, and changing it forces every user to uninstall." >&2
  exit 1
fi

# apksigner is a shell wrapper around a jar, so it needs a JRE on PATH. This Mac has no
# JDK on the default path; JAVA_HOME is set for gradle anyway, so reuse it.
if [ -n "${JAVA_HOME:-}" ]; then
  PATH="$JAVA_HOME/bin:$PATH"
fi
command -v java >/dev/null || { echo "error: no java on PATH; set JAVA_HOME" >&2; exit 1; }

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)}}
apksigner=$(ls "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
[ -x "$apksigner" ] || { echo "error: no apksigner under $sdk/build-tools" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The artifact from the tag's own Release run, so what gets signed is what CI built from
# a clean checkout at that tag — not whatever this working tree happens to hold.
run=$(gh run list --workflow=release.yml --branch "$tag" --status success \
  --limit 1 --json databaseId --jq '.[0].databaseId')
if [ -z "$run" ]; then
  echo "error: no successful Release run for $tag — is it still building?" >&2
  exit 1
fi
gh run download "$run" --name apk --dir "$work"

unsigned="$work/imogen-$tag-unsigned.apk"
signed="$work/imogen-$tag.apk"
[ -f "$unsigned" ] || { echo "error: $unsigned missing from artifact" >&2; exit 1; }

# AGP already zipaligned it and apksigner preserves alignment, so signing is the only step.
"$apksigner" sign --ks "$keystore" --out "$signed" "$unsigned"
"$apksigner" verify --print-certs "$signed"

( cd "$work" && shasum -a 256 "imogen-$tag.apk" > SHA256SUMS && cat SHA256SUMS )

gh release upload "$tag" "$signed" "$work/SHA256SUMS" --clobber
echo "Attached imogen-$tag.apk to $tag"
