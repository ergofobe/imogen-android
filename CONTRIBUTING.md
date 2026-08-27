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
