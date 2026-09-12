# Build, devices, and distribution

Operational reference. Read the signing section before updating a device containing data.

## Build & Run

> Building locally is optional - every push to `master` produces a downloadable APK.
> See [CI - GitHub Actions](#ci--github-actions).

### Prerequisites

- **JDK 17** - `C:\tools\jdk17\jdk-17.0.19+10`
- **Android SDK** - `C:\tools\android-sdk`
- **Device:** Xiaomi Pad 8 (Android 16, wireless ADB `192.168.1.7:5555`)

### Build APK

```powershell
$env:JAVA_HOME = 'C:\tools\jdk17\jdk-17.0.19+10'
$env:ANDROID_HOME = 'C:\tools\android-sdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:assembleField --no-daemon --max-workers=4
```

Output: `app/build/outputs/apk/field/PalmAnnotate-field-v<version>.apk`
(for example `PalmAnnotate-field-v0.3.42.apk`). The version is part of the filename
(see [Versioning](#versioning)), so don't hardcode it; resolve the newest APK instead.

Without the signing secrets configured the name gains a `-NOKEY` suffix
(`PalmAnnotate-field-v0.3.42-NOKEY.apk`). That build runs, but it cannot update - or be
updated by - an APK signed with the real key. See [Distribution signing](#distribution-signing).

The field variant is not debuggable, keeps R8/resource shrinking disabled, and uses
`dev.sawitulm.palmannotate.field` so installing it cannot overwrite the existing debug
app's private dataset.

### Install & Launch

```powershell
$apk = Get-ChildItem 'app/build/outputs/apk/field/PalmAnnotate-field-v*.apk' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 install -r $apk.FullName
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell am force-stop dev.sawitulm.palmannotate.field
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell monkey -p dev.sawitulm.palmannotate.field -c android.intent.category.LAUNCHER 1
```

### Run Tests

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

## Device Testing

### Xiaomi Pad 8 (Primary Test Device)

- **ADB:** Wireless at `192.168.1.7:5555`
- **Android:** 16
- **Package:** `dev.sawitulm.palmannotate.debug`
- **Screen:** 2880×1800 (landscape)
- **Notes:** R8 causes Orbbec preview freeze; keep minification OFF

### Motorola moto g45 5G (Device 2, Handphone)

- **ADB:** Wireless at `192.168.1.3:42309` (the connection port changes when wireless
  debugging restarts; re-check it with `adb mdns services` if connect fails)
- **Android:** 15
- **Model:** `moto g45 5G` (`device:fogos`, `product:fogos_gpn`)

### Xiaomi Pad 6 (Secondary Test Device)

Last connected: 12 September 2026. Discover the current port with adb mdns services before connecting.

- **ADB:** Wireless at `192.168.1.2:45157` (both the address and the port change when wireless
  debugging restarts; re-check them in Developer options if connect fails)
- **Model:** `23043RP34G` (`device:pipa`)
- **Package:** `dev.sawitulm.palmannotate.debug`

### ADB Commands

```powershell
# Connect
& 'C:\tools\android-sdk\platform-tools\adb.exe' connect 192.168.1.7:5555

# Check connection
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 devices

# View logs
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 logcat | Select-String -Pattern "DedupPerf|CanvasPerf"

# Clear logs
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell logcat -c

# Force stop
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell am force-stop dev.sawitulm.palmannotate.debug

# Take screenshot
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell screencap -p /sdcard/screenshot.png
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 pull /sdcard/screenshot.png .
```

## Versioning

PalmAnnotate uses **Semantic Versioning (SemVer)** with auto-increment:

```
versionName = "MAJOR.MINOR.COMMIT_COUNT"   e.g. "0.2.35"
versionCode = COMMIT_COUNT                  e.g. 35
```

- **PATCH** (`.35`) - auto, derived from `git rev-list --count HEAD`. Every commit increments it.
- **MAJOR.MINOR** (`0.2`) - manual, set in `app/build.gradle.kts` → `val majorMinor = "0.2"`.

### When to bump MAJOR vs MINOR

| Bump | When | Example |
|------|------|---------|
| **MINOR** (`0.2` → `0.3`) | New feature set is complete and usable: new screen, new workflow, significant UX improvement. Accumulate several small changes, then bump once when the feature set is "done". | Carousel editor done, depth viewer added, export pipeline complete |
| **MAJOR** (`0.x` → `1.0`) | App is production-ready: field-tested, stable, no known data-loss bugs, suitable for real annotation work. Also: breaking changes to data format or DB schema that require migration. | First field release, or v2.0 with new DB schema |

### Rules of thumb

1. **Don't bump MINOR for every commit.** Accumulate related changes, bump when a coherent feature set is done.
2. **PATCH is free** - it auto-increments, so you never think about it.
3. **Stay at `0.x` while in active development.** Bump to `1.0` only when the app is field-ready.
4. **Commit message convention** (optional but helpful):
   - `feat:` / `fix:` / `perf:` / `docs:` prefixes help when reviewing git log.

### How to bump

Edit `app/build.gradle.kts`:
```kotlin
val majorMinor = "0.3"   // ← change this
```
Then commit. The build will produce e.g. `PalmAnnotate-debug-v0.3.36.apk`.

## CI - GitHub Actions

Local builds are no longer the only way to get an APK. Two workflows live in `.github/workflows/`.

| Trigger | Android Build | Release |
|---------|---------------|---------|
| Push to `master` | ✅ APK as workflow artifact (30d) | ❌ |
| PR to `master` | ✅ | ❌ |
| Push tag `v*` | ❌ (branch-filtered) | ✅ tag + GitHub Release |
| Actions → Run workflow | ✅ | ✅ (derives the tag from the build) |

Build variants: `field` (collection), `debug` (local development), `trace` (side-by-side
diagnostics on a tablet whose old debug app is signed with a different key). See the
invariants below for which one is safe to hand to an operator.

**Getting a CI APK:** Actions tab → pick the run → Artifacts. Or `gh run download`.
Release assets are the raw `.apk`; workflow artifacts are ZIP-wrapped by GitHub, so
the byte counts differ (~83 MB vs ~44 MB) for the *same* build. Install from a Release
to skip the unzip.

**Releases are deliberately manual.** `versionCode` increments on every commit, so
auto-releasing each push would bury the one build that was actually field-verified
under dozens of near-identical ones. Push daily → artifact; cut a Release only for a
build you intend to carry into the field.

### Invariants - do not break these

- **`fetch-depth: 0` in both checkouts is REQUIRED, not cosmetic.** `app/build.gradle.kts`
  derives `versionCode`/`versionName` from `git rev-list --count HEAD`. GitHub's default
  shallow clone (depth 1) makes that return `1` and ships a silently downgraded
  `v0.3.1` / `versionCode 1` APK.
- **CI never builds `release`.** `android-build.yml` builds `field`, `debug` and `trace`;
  `release.yml` publishes `field` + `trace` only. All keep R8 and resource shrinking off, and
  each has its own application id so none can overwrite another's app-private dataset. Only
  `field` is non-debuggable. `field`/`trace` are signed with the persistent distribution key
  when the secrets are present - see [Distribution signing](#distribution-signing).
- **Only the `field` APK may be used for dataset collection.** `trace` is `initWith(debug)`
  and therefore debuggable, which costs roughly the 19 ms median frame measured in
  `FIELD_REPORT_20260727.md` §4.5.
- **The release asset named `PalmAnnotate-debug-v<version>.apk` carries applicationId
  `dev.sawitulm.palmannotate.trace`.** This is deliberate (`release.yml:52-53`): `trace` is the
  side-by-side diagnostic app that replaces `debug` in releases, so it ships under the name
  operators recognise. Don't "fix" the name - but do remember that the *variant* is `trace`
  when reading logs, matching signatures, or issuing `pm uninstall`.
- **A runner-generated debug keystore is not a stable update identity.** `release.yml` now
  **fails before building** when the signing secrets are absent, and Gradle renames an
  unsigned distributable APK `…-NOKEY.apk`. Compare certificate SHA-256 digests with
  `apksigner verify --print-certs`; a mismatch forces uninstall and would remove that
  package's app-private data.
- **Only ONE variant owns `USB_DEVICE_ATTACHED`.** The filter lives in
  `app/src/field/AndroidManifest.xml`, not `src/main`. With all three packages installed, a
  shared filter raised a package chooser on every camera plug-in and bound the USB permission
  to whichever app was tapped. `debug`/`trace` still open the camera through
  `OrbbecManager.requestPermission()` (runtime `UsbManager`), which this does not affect.
  `VariantManifestPolicyTests` fails if a second source set ever claims the filter.
  **Deployment precondition:** this only removes the chooser once *every installed* variant is
  rebuilt from this commit. Tablet `b98cea56` still has the older `.debug` v0.3.41 (which holds
  the 27 Jul data and must not be uninstalled) - its merged manifest still declares the filter,
  so the chooser persists until that package is updated in place. It is debug-signed today, so
  updating it in place is possible. Verify on the device; it cannot be checked from CI.
- **`android:allowBackup` is `false`, deliberately.** The dataset under `getExternalFilesDir`
  is multiple GB - far past Auto Backup's 25 MB quota - and carries field GPS and operator
  names. A restore could not work anyway: SAF grants are not restored and a restored Room DB
  would reference files that were never copied. `backup_rules.xml` and
  `data_extraction_rules.xml` exclude every domain (cloud backup *and* device transfer) so
  re-enabling backup cannot silently expose a domain. The sanctioned recovery path is the
  dataset ZIP / SAF mirror.
- **`release.yml` fails the run if a pushed tag disagrees with the built version.** That
  guard exists so a Release can never carry an APK whose internal version differs from
  its label. Don't relax it.
- **`git push --follow-tags` triggers both workflows** (branch push + tag push) - correct
  output, one wasted build. Push commit and tag separately.

### Distribution signing

Android refuses `install -r` when the signer changed. The only way past it is `pm uninstall`,
which deletes the package's app-private storage - i.e. the collected dataset. So `field` and
`trace` are signed with one persistent key, sourced from repository secrets.

| Secret / Gradle property | Contents |
|---|---|
| `PALMANNOTATE_KEYSTORE_BASE64` | base64 of the `.jks` (used by CI) |
| `PALMANNOTATE_KEYSTORE_PATH` | path to the `.jks` (local alternative to the blob) |
| `PALMANNOTATE_KEYSTORE_PASSWORD` | store password |
| `PALMANNOTATE_KEY_ALIAS` | key alias |
| `PALMANNOTATE_KEY_PASSWORD` | key password |
| `PALMANNOTATE_SIGNING_CERT_SHA256` | **required to publish**; `release.yml` fails if the built cert differs |

> ### ⛔ Read before configuring the secrets
>
> A tablet in the field already has `dev.sawitulm.palmannotate.field` installed **with a dataset
> in it** (`FIELD_REPORT_20260727.md` §5.8: tablet `b98cea56`, `.field` v0.3.60,
> 152 committed trees). The current field status and remaining hardware checks are recorded in
> that addendum.
> Introducing a *different* signer is not a packaging detail: `install -r` starts failing with
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way to install the new build is
> `pm uninstall`, which deletes that dataset. Generating a fresh key without checking would
> **cause** the data-loss event this whole workstream exists to prevent.
>
> So the first question is not "which key do I create" but "which key is already on the device".

**Step 1 - identify the installed signer (do this first, on the device).**

```powershell
$adb = 'C:\tools\android-sdk\platform-tools\adb.exe'
$installed = & $adb -s 192.168.1.7:5555 shell pm path dev.sawitulm.palmannotate.field
& $adb -s 192.168.1.7:5555 pull ($installed -replace '^package:','') installed-field.apk
& 'C:\tools\android-sdk\build-tools\35.0.0\apksigner.bat' verify --print-certs installed-field.apk
```

**Step 2 - prefer adopting that signer.** If the digest matches a keystore you still hold (for a
locally built APK that is `~/.android/debug.keystore`, alias `androiddebugkey`, store/key password
`android`), use **that** file as `PALMANNOTATE_KEYSTORE_BASE64`. The identity then does not change,
every installed app updates in place, and nothing has to be uninstalled.

**Step 3 - only if the existing signer is unrecoverable**, generate a new one - and treat it as a
migration, not a config change:

1. Export a dataset ZIP from every device that holds data and **verify it off-device** (open the
   ZIP, count trees) before the first key-change install.
2. Record the pre-change digest from step 1 in the release notes.
3. Then, and only then, `pm uninstall` + install the new build.

```powershell
$env:JAVA_HOME = 'C:\tools\jdk17\jdk-17.0.19+10'
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
    -keystore palmannotate-release.jks -alias palmannotate `
    -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass '<store-pw>' -keypass '<key-pw>' `
    -dname "CN=PalmAnnotate, O=SawitULM, C=ID"

# Value for PALMANNOTATE_KEYSTORE_BASE64
[Convert]::ToBase64String([IO.File]::ReadAllBytes('palmannotate-release.jks')) | Set-Clipboard

# Value for PALMANNOTATE_SIGNING_CERT_SHA256 (required) - the SHA256 line
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore palmannotate-release.jks -alias palmannotate
```

Back the `.jks` up off the machine and add it to the repository's Actions secrets
(Settings → Secrets and variables → Actions). **Never commit it.**

The pin is mandatory because `field` and `trace` are signed by the same config, so the
field-vs-trace comparison in `release.yml` can never fail on its own. The pin is the only guard
that can catch a *changed* key.

Behaviour without the secrets:

- **Local builds keep working.** Gradle falls back to the debug keystore and names the output
  `PalmAnnotate-field-v<version>-NOKEY.apk`. `BuildConfig.SIGNING_IDENTITY` becomes
  `EPHEMERAL_DEBUG` and `PalmAnnotateApp` logs a warning naming the data-loss consequence.
- **`release.yml` refuses to run.** The secret check is the first step, before checkout, and a
  `-NOKEY` asset is rejected again just before upload.
- **`android-build.yml` still builds** (a PR from a fork has no secrets); the `-NOKEY` name is
  the marker that the artifact cannot update anything.

`release.yml` additionally rejects a `versionCode` that is not greater than the highest already
published (the rebase/force-push case), except when re-running the same tag, and records the
certificate SHA-256 in the release notes.

### CI does NOT replace on-device verification

A green CI run means it compiles and the unit tests pass. It says **nothing** about the
Orbbec live depth preview, which no runner can exercise. Every APK still needs the
on-device checklist before field use. See the 0% error tolerance block below.
