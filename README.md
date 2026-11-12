# PanelVault 0.4

Offline Android comic library and reader for CBZ, CBR (RAR4/RAR5) and PDF.

## Library

Navy gradient library, portrait cover galleries, dimensional original wordmark and vault-panel icon. Open the left navigation with the menu button; dismiss with Back, the backdrop or Close. Browse Comics, Series, Rulebooks, All and Continue Reading. The cover-size slider adapts the column count to screen width and saves its value immediately. Search, sort, list view and incremental loading remain available.

Reader page controls, gestures, zoom, PDF rendering, archive extraction and existing filename-based reading progress are retained.

## Builds and permanent signing

Use Java 17, Android SDK 35 / build-tools 35.0.0 and Gradle 8.10.2.

Development builds use `com.panelvault.reader.dev`, so test installs never overwrite the personal library. Production uses the original `com.panelvault.reader` ID.

Release builds require these environment variables:

- `PANELVAULT_KEYSTORE`: absolute path to the permanent PKCS12 file
- `PANELVAULT_KEY_PASSWORD`: keystore and key password
- `PANELVAULT_VERSION_CODE`: increasing integer (first stable build is 100)

Key alias: `panelvault`. Run `gradle :app:assembleRelease :app:testDebugUnitTest :app:lintRelease`.

GitHub Actions reads `PANELVAULT_KEYSTORE_BASE64` and `PANELVAULT_KEY_PASSWORD` from repository Actions secrets. It derives versionCode as 100 + the workflow run number. It fails rather than generating a replacement key. `signing-cert.sha256` pins the public signing certificate. Never commit the private key or passwords. Preserve an offline backup: losing the key prevents compatible updates.

Old development APKs were signed with temporary keys. The new production identity cannot update one of those installations unless its exact original private key can be recovered. Do not uninstall a personal installation without preserving its library and progress; Android removes private app data on uninstall. Future production APKs use the same permanent certificate and increasing version codes.

## Windows synchronization foundation

Export catalog opens Android's save dialog. Schema v2 uses SHA-256 content IDs, independent of filename and device path, plus filename, title, inferred series, format, byte count, modified time, zero-based saved page and lastRead timestamp. Hashing/export runs off the main thread. This is a portable metadata snapshot, not a sync transport or backup of comic content.

`CatalogIdentity` separates portable identity from the UI. A future Windows client can index files by the same digest and match catalog entries. Conflict resolution, pairing, change journals and progress import remain future work; existing reading progress is deliberately preserved in the current local store.

## Verification

CI compiles and lints Android builds, tests portable content identity, and runs an Android 35 tablet emulator test for drawer navigation, cover preference and CBZ reading/progress. Device reports and a library screenshot are uploaded as artifacts. Actual reader CBZ/CBR/PDF behavior should also be checked on the user's hardware before a production rollout.
