# Releases

Gallery follows akihz's manual beta/stable release process. Pushes and pull requests
on `main` and `beta` run CI. A push never creates a tag or release.

## Publish a beta

1. Update `CHANGELOG.md` under `[Unreleased]` with short user-facing notes.
2. Push the release source to `beta`.
3. Run `gh workflow run release-beta.yml --ref beta`.

The workflow publishes the next `vVERSION_NAME-beta.N` prerelease. The first beta
is `v1.14.3-beta.1`. Beta notes come from `[Unreleased]`.

## Publish a stable release

1. Bring the tested beta changes into `main`.
2. Add a dated changelog section matching `VERSION_NAME` in `gradle.properties`.
3. Run `gh workflow run release-main.yml --ref main`.

The workflow publishes `vVERSION_NAME` as a stable release. Before the next release
cycle, bump `VERSION_NAME` in `gradle.properties`. Version codes use seconds since
2024-01-01, as in akihz, so later releases can update earlier ones on either channel.
All APK variants use the same version code, package and signing certificate.

## Gates and assets

Both release workflows reject the wrong branch or missing changelog notes before
building. They run their own lint and unit test gates, then build the FOSS flavor.
Release scripts also run in CI. Asset preparation resolves the MediaPipe `SystemInfo` class through the R8 mapping
and checks its declared `platform_` field in every APK with `dexdump`. Protobuf reads
this field by reflection. A missing or renamed field stops publishing before tag creation. No local Android build is required.

Each release includes signed arm64-v8a, armeabi-v7a, x86, x86_64 and universal APKs,
SHA-256 checksums and `release-metadata.json`. The metadata contains the exact
extracted changelog Markdown, changelog URL, package, version, signing certificate
fingerprint and asset URLs, sizes and hashes. AI search requires a 64-bit process;
the other APKs support the gallery and filename search.

The package is `gallery.anlaki.dev`. The Kotlin namespace remains
`org.fossify.gallery` to preserve the existing source and resources. Debug builds
use `gallery.anlaki.dev.debug` and the documented development key. Signed release
APKs use the repository secrets below and can coexist with official Fossify.

## Repository secrets

- `KEYSTORE_BASE64`: Base64-encoded release keystore.
- `KEYSTORE_PASSWORD`: Keystore password.
- `KEY_ALIAS`: Signing key alias.
- `KEY_PASSWORD`: Signing key password.
- `GRADLE_CACHE_ENCRYPTION_KEY`: Optional Gradle cache encryption key.

The keystore is decoded only in the runner's temporary directory, removed after
the build and excluded from uploaded artifacts. APK signatures, package names
and versions are checked before any release tag is created.
