# Gallery agent guidelines

## Session and code quality

- Apply `unslop` and `i-have-adhd` when available. Use plain language and short updates.
- Apply `maintainable-engineering` for non-trivial code changes.
- Run the `cleanup` skill after code changes. Scope it to the session diff, verify
  references before removing code, and check for duplication and unused helpers.
- Aim for about 350 lines per Kotlin/Java file. Split mixed responsibilities,
  rather than splitting tightly related code just to meet a line count.
- Keep each class focused. Follow the existing package structure and Kotlin style.
- Add KDoc for new public functions. Comments explain constraints and unusual
  choices, rather than repeating what the code does.

## Project and layout

Gallery is a GPL-licensed Fossify Gallery fork with optional local AI search.
Preserve upstream copyright, license and third-party notices.

- `app/src/main/kotlin/org/fossify/gallery/activities/`: Android screens.
- `app/src/main/kotlin/org/fossify/gallery/ai/`: Model storage, inference, indexing,
  downloads, ZIP import and background work.
- `app/src/main/res/`: Android layouts, strings and other resources.
- `app/src/test/`: JVM and Robolectric behavior tests.
- `build-logic/`: A narrow Commons warning adapter and its bytecode tests.
- `scripts/`: Release versioning, APK verification, metadata and script tests.
- `.github/workflows/`: CI and manual stable/beta release entry points.

Commons 7.1.3 hardcodes a fake-app warning and store redirect with no override
hook. `CommonsForkNoticeVisitorFactory` replaces only that method during Android
packaging with `ForkNoticeDialog.show`. Keep its upstream-signature guard and
bytecode tests when updating Commons. Do not spoof the application ID to hide it.

Release application ID is `gallery.anlaki.dev`; debug appends `.debug`. Keep the
Kotlin namespace `org.fossify.gallery`. AI is off by default and runs on-device.
Do not upload media or queries. Model download is the only AI network request.

## Android/Termux environment

There is no local Android build setup in this workspace. Do not install an SDK,
Gradle distribution or other Android build tools. Use GitHub Actions to verify
Android changes, and inspect the actual results before reporting success.
Robolectric tests use API 34. `prepareRobolectricSdk` resolves the pinned Android
runtime through Gradle and its cache, using Google's Maven Central mirror for
that module only. Tests use `robolectric.offline=true`; update the pinned runtime
if changing test SDKs or Robolectric's instrumentation version. Keep this setup
so test JVMs do not fetch uncached SDK jars and hit Maven Central rate limits.

Python release tooling tests can run locally:

```sh
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

On CI or an already configured Android build machine, use JDK 21 and SDK 36:

```sh
./gradlew :app:lintFossDebug :app:testFossDebugUnitTest
./gradlew :app:assembleFossDebug
```

Release builds use `:app:assembleFossRelease -PABI_SPLITS=true -PciBuild` with
version and signing values supplied by the workflow. Preserve executable mode
on `gradlew`. Never commit release signing keys, tokens or passwords.

## CI and releases

Follow the same channel rules as `/home/projects/akihz`:

- `CI` runs lint and unit tests for pushes and pull requests on `main` and `beta`,
  and supports manual dispatch. Pushes never create tags or releases.
- `release-beta.yml` is manual-only and accepts only the `beta` branch. Publish
  `-beta.N` releases with `prerelease: true`.
- `release-main.yml` is manual-only and accepts only `main`. Stable releases have
  `prerelease: false`.
- Both entry points use the shared release workflow and run lint and unit tests
  before signing or publishing. Keep those gates even when separate CI passed.
- `VERSION_NAME` in `gradle.properties` defines the next stable version. Beta
  counters are derived from tags. Bump the base version after a stable release.
- Use the same package and signing key on both channels so updates work.
- Verify APK signatures, package and versions before creating the release tag.
- See `RELEASING.md` for commands, assets, secret names and version-code rules.

## Changelog and metadata

- Keep `[Unreleased]` non-empty before publishing beta changes.
- Before a stable release, add a non-empty `## [x.y.z] - YYYY-MM-DD` section
  matching the version being published.
- Keep changelog bullets short and user-facing. Apply `unslop` to release notes.
- Use `Added` or `Changed` for work that has not shipped. Reserve `Fixed` for
  behavior that was already released.
- Missing or empty changelog sections must fail before tag creation.
- Source release notes from the changelog, not generated commit history.
- Include the exact extracted Markdown in `release-metadata.json`'s
  `releaseNotes`, along with the canonical changelog URL.

## Completion

Check the session diff for accidental files and credentials. Run release script
tests when touching automation. Wait for CI and, when requested, the release
workflow. Report actual check outcomes and link the published release. Device
inference and OEM background behavior require a phone; JVM tests do not prove them.
