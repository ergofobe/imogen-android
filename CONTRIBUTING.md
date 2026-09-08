# Contributing

## Getting a build

```bash
git clone --recurse-submodules https://github.com/ergofobe/imogen-android
cd imogen-android
./gradlew :app:assembleDebug
```

`imogen-sdk/` is a git submodule, included as a composite build. There is no published
`com.imogen:imogen-sdk` artifact yet, and vendoring a copy of the client would mean two
copies of the API contract drifting apart — which is the exact failure the conformance
suite in that repository exists to prevent.

Changing the SDK means committing there first, then bumping the submodule pointer here:

```bash
cd imogen-sdk && git pull && cd ..
git add imogen-sdk && git commit -m "Move to the current SDK"
```

## Checks

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleRelease   # exercises R8, which finds different things
```

CI runs all four. Lint reports newer AndroidX versions than the ones pinned; see the note
in `gradle/libs.versions.toml` for why those pins are deliberate.

## Releasing

Tagging `vX.Y.Z` builds the release APK in CI and leaves it as a workflow artifact. It is
unsigned, and no workflow can sign it: the release key lives on the maintainer's machine
and never becomes a CI secret. Signing and attaching is one local step, run once CI is
green:

```bash
scripts/sign-release.sh vX.Y.Z
```

That pulls the artifact from the tag's own Release run — so what ships is what CI built
from a clean checkout, not whatever a working tree held — signs it, prints the certificate,
and uploads the APK and a `SHA256SUMS` to the release. `apksigner` prompts for the keystore
password, so run it yourself rather than handing it to an agent.

It must be the **same key every release**. Android identifies an app by its signing
certificate, so a new key is a new app: existing users get
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` and have to uninstall, losing their local library and
paired tokens. This is also why CI does not publish a debug build — the debug keystore is
generated per runner, so every release would be signed by a different key.

## Where things go

Logic worth testing goes in a plain class, not in a composable. `TimelineIndex`,
`AccountBook`, `TokenSet` and `normalizeServerUrl` are all tested without an emulator, and
anything with arithmetic or a decision in it should be able to join them.

Composables take what they need as parameters and hand events back out. A composable that
reaches for the application container is a composable that cannot be previewed and cannot
be tested.

## Scope

This is a user client, on purpose. Server administration — accounts, invitations, the
processing queue, connected applications, public links — lives in the web interface behind
a browser session, and the app asks for the scopes a photo client needs and no more.
Please do not add administration here.
