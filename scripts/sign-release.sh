#!/usr/bin/env bash
# Sign the APK CI built for a tag, and attach it to that tag's release.
#
# Run this yourself, in your own terminal: apksigner prompts for the keystore password,
# and the whole point of doing it here rather than in a workflow is that the release key
# stays on this machine. Nothing in .github/ can produce a signed build.
#
#   scripts/sign-release.sh v0.2.3
#
# The keystore defaults to ~/.config/imogen/imogen-release.p12; override with
# IMOGEN_KEYSTORE. Under ~/.config because that is the sort of path a dotfile backup
# already covers — ~/.android is a cache directory and yours probably does not.
set -euo pipefail

tag=${1:-}
if [ -z "$tag" ]; then
  echo "usage: scripts/sign-release.sh vX.Y.Z" >&2
  exit 2
fi

keystore=${IMOGEN_KEYSTORE:-$HOME/.config/imogen/imogen-release.p12}
if [ ! -f "$keystore" ]; then
  echo "error: no keystore at $keystore" >&2
  echo "It must be the same key every release — Android identifies the app by its" >&2
  echo "signing certificate, and changing it forces every user to uninstall." >&2
  exit 1
fi

# apksigner is a shell wrapper around a jar, so it needs a JRE on PATH. This Mac has no
# JDK on the default path; JAVA_HOME is set for gradle anyway, so reuse it — and when it is
# not set, fall back to the JDK this machine builds with. Signing is the one release step a
# human runs by hand, so it is where the variable is most likely missing. Nothing in the repo
# pins this path — CI uses actions/setup-java — so test for a runnable java under it rather
# than assuming one: a machine without it gets the error below, not a JAVA_HOME the script
# invented and then told you to change.
brew_jdk=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
if [ -z "${JAVA_HOME:-}" ] && [ -x "$brew_jdk/bin/java" ]; then
  JAVA_HOME=$brew_jdk
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export JAVA_HOME
  PATH="$JAVA_HOME/bin:$PATH"
fi

# Run java rather than looking for it: macOS ships /usr/bin/java as a stub that exists and
# is executable whether or not a JDK is installed, so `command -v java` passes on a machine
# with no runtime and the failure surfaces later from apksigner as Apple's "Unable to locate
# a Java Runtime" — which names neither this script nor JAVA_HOME.
if ! java -version >/dev/null 2>&1; then
  echo "error: no working Java runtime${JAVA_HOME:+ (JAVA_HOME=$JAVA_HOME)}" >&2
  echo "Set JAVA_HOME to a JDK, e.g. $brew_jdk" >&2
  exit 1
fi

# Every lookup below is written so it cannot fail, because under `set -e` a failing command
# substitution kills the script *at the assignment* and the guard written to explain it never
# runs — an operator holding the release key gets exit 1 and an empty terminal. `sed` on a
# missing local.properties and `ls` on a glob that matches nothing both fail exactly when the
# thing being looked up is absent, which is the one case the guards exist for.
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk" ] && [ -r local.properties ]; then
  # `-r` rather than `-f`, and `|| sdk=` behind it: a local.properties that exists but cannot
  # be read would otherwise fail the sed and take the script out here, which is the silent
  # exit this whole block exists to remove.
  sdk=$(sed -n 's/^sdk\.dir=//p' local.properties) || sdk=
fi
if [ -z "$sdk" ]; then
  echo "error: no Android SDK location" >&2
  echo "Set ANDROID_HOME (or ANDROID_SDK_ROOT), or run this from a checkout whose" >&2
  echo "local.properties has sdk.dir — it is gitignored, so a fresh worktree has none." >&2
  exit 1
fi

# printf prints the unmatched pattern rather than failing, so the guard below always gets to
# speak. sort -V because build-tools directories are versions: 9.0.0 sorts above 34.0.0
# lexically.
apksigner=$(printf '%s\n' "$sdk"/build-tools/*/apksigner | sort -V | tail -1)
[ -x "$apksigner" ] || { echo "error: no apksigner under $sdk/build-tools" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The artifact from the tag's own Release run, so what gets signed is what CI built from
# a clean checkout at that tag — not whatever this working tree happens to hold.
if ! run=$(gh run list --workflow=release.yml --branch "$tag" --status success \
  --limit 1 --json databaseId --jq '.[0].databaseId'); then
  # Same trap as above: without the `if`, errexit would exit here and the guard below — the
  # one that knows what this query was for — would never run. gh has said something of its
  # own by now; this says which step of the release it stopped.
  echo "error: could not ask GitHub for the Release run of $tag" >&2
  exit 1
fi
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
