<div align="center">
  <h1>imogen for Android</h1>
  <p><strong>Your photo library, on your own server — on your phone.</strong></p>
</div>

A native client for [imogen](https://github.com/ergofobe/imogen-server), written in Kotlin
and Jetpack Compose. It browses the library, backs up what the camera takes, and does both
against as many servers as you have accounts on.

- **Pair by pointing the camera at a QR code** — no hostname to type
- **Several accounts, several servers** — switch between them from Settings
- **Backup to more than one** — choose two accounts, get two copies
- **Phone and tablet** — a bottom bar on one, a rail and two panes on the other
- **A timeline that survives fifty thousand photographs** — see below
- **No Google Play services** — the QR reader is ZXing, the maps are absent, nothing phones home

Administration is deliberately absent. Accounts, the processing queue, connected
applications and public links are managed in the web interface, behind a signed-in browser
session; this app asks for the scopes a photo client needs and no more, so a lost phone is
not a lost server.

---

## Installing it

There is no release build yet. To build one:

```bash
git clone --recurse-submodules https://github.com/ergofobe/imogen-android
cd imogen-android
./gradlew :app:assembleDebug
```

`--recurse-submodules` matters: the client library lives in
[imogen-sdk](https://github.com/ergofobe/imogen-sdk) and is built from source as part of
this build. If you have already cloned without it, `git submodule update --init`.

You will need an Android SDK. `local.properties` should point at it:

```properties
sdk.dir=/Users/you/Library/Android/sdk
```

## Pairing

The hard part of installing a self-hosted photo app is the first screen, where it asks a
phone keyboard for a hostname somebody chose themselves. imogen does not.

1. On a computer, open imogen and go to **Settings → Devices → Pair a device**.
2. On the phone, open imogen and tap **Scan a pairing code**.
3. Point the camera at the square.

What crosses the camera is a one-time ticket, not a token. It carries the server address
and a code that lives five minutes and works once; the app registers a client for itself,
turns the ticket into an authorization code bound to a PKCE challenge it generated, and
exchanges that for tokens. Photographing somebody's screen gets you nothing, because the
verifier never left the phone.

If the phone is the thing looking at the web interface, the same dialog offers the ticket
as a link — tapping it opens the app directly.

Failing all that, **Enter a server address** runs the ordinary OAuth flow through the
system browser.

## Backup

**Settings → Photo backup.** Choose which accounts get a copy: each one you turn on gets
its own, so a family server and a personal one both end up with the photograph.

Uploads are idempotent by content — the server recognises a file it already has and does
not store it twice — and each file carries a `deviceAssetId`, so the app knows what it has
already sent without re-reading the camera roll. Anything at or above 64 MB goes up in
resumable chunks, so a dropped connection costs one chunk of a video rather than the whole
thing.

Wi-Fi only and charging-only are both there, and both default sensibly: Wi-Fi on, charging
off.

## A timeline that scales

A library of fifty thousand photographs cannot be paged into a grid a hundred at a time.
Reaching 2011 that way is four hundred round trips, and the scrollbar lies about how much
there is until the last one lands.

So the grid is not built from photographs. It is built from
`GET /api/v1/assets/timeline`, which returns one row per day with a count — a few thousand
rows for a lifetime, one request, no images. From that the app knows exactly how many cells
there are and where every day begins before fetching a single photograph:

- the grid is the right length from the first frame, so nothing reflows as data arrives
- **the rail** on the right edge is a thumb until you take hold of it, and then it is a
  ruler: year marks spaced by how much of the library each year holds, and the month under
  your thumb named as you drag. A year of nine thousand frames takes more rail than a year
  of two hundred, because that is where its photographs are
- the thumb is positioned by a segment table — a running total of estimated pixel heights,
  a heading plus however many rows each day needs — not by a photograph's position in the
  list. Those are different measurements, and using the wrong one is what makes a scrubber
  jump while the content scrolls smoothly
- days load as they come into view, one request each — jumping to a date five years back
  costs one request, not four hundred
- days scrolled far past are evicted, so scrolling end to end does not end with fifty
  thousand assets in memory
- nothing is fetched *during* a drag, and fetching resumes 150 ms after it settles, so a
  flick across a decade does not ask the server for every day it passes through

The same segment table is what the web timeline is being rebuilt on, so the three clients
describe the library the same way.

## Plain http

A self-hosted server often sits on a home network at an address like
`http://192.168.1.9:3000`, with no certificate because there is no public name to issue one
for. Android refuses cleartext by default, so the app ships a network security
configuration that permits it — and the safety is in the app instead: typing a bare
hostname produces `https`, so plain http happens only when somebody writes `http://`
themselves or a server advertises a plain-http public URL in the pairing code it generated.

Certificates installed on the device are trusted too, because somebody running their own
certificate authority for their own network is exactly the person this application is for.

## Working on it

```bash
./gradlew :app:assembleDebug     # build
./gradlew :app:testDebugUnitTest # tests
./gradlew :app:lintDebug         # lint
```

The interesting logic is deliberately in plain classes rather than in composables, so it
can be tested without an emulator: `TimelineIndex` (the day arithmetic the grid and the
scrubber both run on), `AccountBook`, `TokenSet`, `normalizeServerUrl`.

### The shape of it

```
data/        accounts, sessions, pairing, the keystore-sealed store
backup/      MediaStore scanning, the upload ledger, the WorkManager worker
ui/timeline/ the day index, the grid, the scrubber, the cursor-paged browser
ui/viewer/   the full-screen pager, video, the details sheet
ui/…         albums, people, search, settings, onboarding
```

Dependencies are wired by hand in `ImogenApplication`. There are five of them, they are
all singletons, and none needs swapping at runtime.

### Toolchain

AGP 8.13.2 with Gradle 8.14.3, `compileSdk` 36, `minSdk` 26. Lint will report newer
AndroidX versions on every build; those releases require AGP 9, whose built-in Kotlin
support KSP — and therefore Room's compiler — does not yet work with. The pin is
deliberate and the note is expected.

## Licence

AGPL-3.0-or-later, the same as the server and the SDK.
