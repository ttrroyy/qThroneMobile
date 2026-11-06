# 2.4.2 release preparation

## Scope

- New releases contain APK assets only, plus GitHub's standard source archives.
- Verification metadata moves into a hidden GitHub release-body comment; SHA-256, package, versionCode and APK signer checks remain.
- Release v2.4.1 acts as a one-time bridge: add the 2.4.2 APKs and update its JSON after all APKs are uploaded. Old APK assets remain available.
- Keep the bridge visible to old clients, whose updater only requests the latest 20 releases.
- Preserve the signing key, app.rawline.mobile package and strictly increasing versionCode (2.4.2: 69999).
- Subscription editor: one continuous sheet background, no navigation cross; content gestures scroll, only the top handle drags the sheet.
- Automatic CSQTT protocol choice: Legacy first, then a separate device-bound transport after explicit protocol rejection or the existing zero-worker WRAP timeout verdict.
- No protocol selector in the product UI. Preserve device ID, password, hashes and per-launch epoch across the retry.
- Latency probes and normal connections share the retry path; retain the existing active-session collision guard.
- Shared qWDTT account binding for all three VK carriers; Auto JS gets its token only at launch, and explicit Auto API takes priority over an old Auto JS authentication field.
- HTTP token lookup falls back to the original WebView after network failure, retains the login user agent and retries one rejected API token once.
- Theme preference input/selection dialogs through the common activity callback; preserve input titles, modifiers, validation and switch resources.
- Import/export masque:// through the common parser and Android intent handler. Import the supplied Clash template structure for all 15 WireGuard/AmneziaWG/Masque profiles with synthetic test identities.

## Native sources

Legacy Rust and deployment remain pinned to 2066156119a5afe7d63e104aefdc8e0849999d03.

Compatibility Rust is separately pinned to original CSQTT v2.1.9, 446293aa2e873ac5323ef6fd2316d9b81d966c11.
Its device-bound GETCONF includes device ID and CSQTT-WIRE-3, unlike the password-bound Legacy request.
The supplied pre-release APK has the device-ID launch argument and CSQTT-WIRE-3 marker.
Source-level agreement does not yet prove live interoperability with the supplied subscription.

## Checks and pending work

- Local Go selection/redacted-diagnostic tests pass.
- Local Python release-metadata tests and existing release-number tests pass.
- Compatibility/private-FD patch scripts apply to the pinned original source.
- Pending: native client build and original Rust tests for all three ABIs.
- Native workflow automatically verifies provenance, uploads immutable archives, pins them, then promotes unchanged dev to stable and dispatches stable CI.
- Pending: Android unit tests/build; real APK upgrade and real old-server ping/connection.
- Release publisher preserves a verified bridge and can resume an interrupted draft publication.
- Long emulator/UI suites are intentionally excluded.
- XML audit: all 463 declared text-input preferences have titles; no switch layouts/drawables were changed.
- Verified the reported YAML and anonymized regression fixture have identical proxy field structures (13 WireGuard, 2 Masque, 25 aliases).
- Added Kotlin regressions for shared account tokens, API/JS precedence, URI key escaping, YAML templates and bounded recursive/expanded YAML. Execution awaits Android CI because this workstation has no JDK/SDK.
- Native workflow now fetches full history for promotion and runs the original Rust suite sequentially after an upstream UDP fixture timed out during parallel tests.
