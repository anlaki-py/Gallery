# AI natural language search

This fork adds local photo and video search to Fossify Gallery. AI is off by default.

## Use it

1. Install the debug APK. Its application ID is `gallery.anlaki.dev.debug`, so it can coexist with the official Fossify app.
2. Open Settings → AI natural language search and enable it.
3. Download the embedding model, or use **Import model from ZIP** to reuse your existing Edge Gallery model. The download is 484,622,336 bytes (about 485 MB). The default requires an unmetered connection; turn that option off to allow mobile data.
4. Grant access to all photos and videos for a full scan. Selected-media access only indexes the files Android allows. Tap **Scan all photos and videos** if a scan has not already started after download.
5. Describe what you want in the search bar. The search menu switches between AI and filename search. Tapping the status above the results opens AI settings.

Scanning and model downloads can continue after leaving the screen. Settings shows status and progress, pause/resume, retry unreadable files, CPU mode, index clearing and model re-download. Force-stopping the app prevents work until it is opened again. Android and OEM battery controls can defer background work.

Searches and media stay on the device. The model download uses HTTPS to Hugging Face. AI needs a 64-bit Android process (`arm64-v8a` or `x86_64`). The app itself retains Fossify's Android 8 / API 26 minimum; device-level compatibility of this experimental runtime needs testing on older versions.

## Model and upstream sources

- Fossify base: `bde42f4a055205382bff299a5f06a399bc7da99d`
- AI Edge Gallery reference: `eb832659e79c42629cee202b103717c91adf51b4`
- Model: `litert-community/embeddinggemma-2-740m-litert-lm`
- Model revision: `86b903a293f96d042bb92e1b7af333d94114458f`
- File: `embeddinggemma-2-740m.litertlm`
- SHA-256: `e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c`
- [Pinned model download](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/86b903a293f96d042bb92e1b7af333d94114458f/embeddinggemma-2-740m.litertlm)

The model, size and revision come from Edge Gallery's `model_allowlists/1_0_23.json`. The hash was checked against the pinned Hugging Face LFS metadata. The model is downloaded by the app rather than bundled in the APK or source ZIP.

Runtime: MediaPipe `tasks-retrieval:1.2.0`, with LiteRT-LM explicitly aligned to Edge Gallery's `0.18.1` (overriding the older transitive runtime). Embeddings use Edge Gallery's 70 vision tokens, 256 context tokens, L2 normalization and the embedding dimension returned by the model (Edge Gallery declares 768 as its default). Mismatched or invalid vector records are rejected rather than compared. AGP's built-in Kotlin compiler is aligned to Kotlin 2.4.21 to read this runtime's metadata. This is the Universal Embedder path, not a captioning model or text-only embedding model.

## Implementation

`ai/AiModelStore.kt` streams into an app-private `.part` file, resumes exact byte ranges, restarts if the server ignores Range, validates length and SHA-256, and atomically promotes the verified model. It checks free storage, handles HTTP errors/timeouts, bounds retries and keeps interrupted bytes. A post-promotion crash can recover by re-verifying the existing model.

`ai/AiEmbeddingEngine.kt` owns a single native engine on one dedicated thread, including initialization, inference and cleanup. It falls back from GPU to CPU and defaults to CPU on MediaTek/Mali and Exynos hardware. CPU mode can also be chosen manually. The engine closes after 30 idle seconds to release memory. Native calls are synchronous: stopping waits for the current inference to return. A native process crash cannot be intercepted in Kotlin; completed database transactions survive.

`ai/AiMediaReader.kt` enumerates Android's image and video MediaStore collections, including accessible removable volumes. It excludes pending, trashed, hidden, excluded and password-protected folders, including protected ancestors. It does not crawl private app directories, cloud libraries or files hidden from MediaStore by `.nomedia`; Android must expose the media. Decoded images and frames are reduced to 768 pixels on the longest edge. API 27+ uses scaled video decoding; the API 26 fallback briefly allocates the full decoded video frame before resizing, so high-resolution videos can hit its memory limit. ImageDecoder handles orientation on API 28+; the API 26–27 fallback applies EXIF rotation/mirroring.

For each video the scanner extracts the closest decodable frame to 0, halfway, and the last valid microsecond. This is visual search only; speech and other audio are not indexed. Frames are embedded independently. The result score is the highest frame similarity, and each video appears once. Codec precision may cause nearby timestamps in very short videos to decode to the same frame. A file with an unreadable frame is reported as unreadable and can be retried.

`ai/AiIndexDatabase.kt` stores normalized float32 embeddings in a separate SQLite WAL database in `noBackupFilesDir`. It does not alter Fossify's Room schema. Each media item and all its frame vectors commit together. URI, modified time, byte size, video duration and model/preprocessing version identify a record. Changed files are invalidated; files removed from a complete successful snapshot are pruned. A failed query cannot turn into a successful empty snapshot. Before committing, the scanner rechecks modified time and size to avoid publishing embeddings for a file edited during inference. Such files are retried on the next scan.

Unreadable files are retained as failures with a 24-hour cooldown instead of breaking the rest of a scan. The retry button resets that cooldown. Global inference, storage and permission failures stop the scan with an actionable status. Index clearing cancels scanning and waits for the native thread before removing embeddings. It leaves the media and model intact and pauses automatic scanning until resumed.

`ai/AiWork.kt` uses unique WorkManager work for download and scanning, preventing duplicate work. Scans use a foreground dataSync notification, battery-not-low and storage-not-low constraints, and checkpointed six-minute chunks. Android foreground-service and job quotas still apply across chunks. A process restart resumes from committed files. Opening MainActivity or MediaActivity queues a new-media check when enabled, unpaused and model-ready. Disabling AI cancels work and retains model/index data.

`activities/SearchActivity.kt` debounces AI queries, cancels older requests, applies current visibility rules, and returns the closest 200 results in similarity order. It deliberately does not apply a universal similarity cutoff; the status explains that closest matches may include unrelated media, as semantic similarity is not a calibrated probability. Ranking streams one vector at a time into a bounded heap. It can search the completed part of the index during scanning. A folder search keeps its folder scope; the home search covers accessible visible media. The existing filename search stays available in the search menu.

## Build and checks

Use JDK 21, Android SDK 36 / Build Tools 36.0.0, and the included Gradle wrapper. Configure `ANDROID_HOME` or a local `sdk.dir`. No Google account, API key, token or cloud inference service is required.

```sh
./gradlew :app:assembleFossDebug :app:testFossDebugUnitTest :app:lintFossDebug
```

APK output: `app/build/outputs/apk/foss/debug/`.

Unit tests cover vector validation and cosine scores, video timestamp units, download verification/resume/cancellation/HTTP failures, atomic media checkpoints, changed/deleted records, visibility filters, permission-query failures, retry state and best-video-frame ranking. See the accompanying validation report for actual executed results.

Before relying on this as a daily gallery, test model download, real image/video inference, screen-off indexing, OEM background behavior, media permissions, file edits/deletions, notification handling and forced process death on the target phone. The development environment has no connected Android device; native model inference and OEM behavior cannot be validated by JVM tests alone.


## Import an existing Edge Gallery model

ZIP the `EmbeddingGemma_2` folder, copy that ZIP to Downloads, then choose **Settings → AI natural language search → Import model from ZIP**. The system document picker grants access to this one file; the app does not need access to Edge Gallery’s private Android/data directory. Downloads is recommended because Android restricts selecting files directly inside another app’s Android/data directory.

Accepted examples:

```text
EmbeddingGemma_2/86b903a293f96d042bb92e1b7af333d94114458f/embeddinggemma-2-740m.litertlm
backup/EmbeddingGemma_2/86b903a293f96d042bb92e1b7af333d94114458f/embeddinggemma-2-740m.litertlm
embeddinggemma-2-740m.litertlm
```

The model is selected by its filename, preferring the pinned revision folder if several revisions are present. Ambiguous duplicate models are rejected. Unencrypted ZIPs up to 2 GiB and 10,000 entries are supported. Arbitrary archive paths are never used for extraction, and unsafe traversal/absolute paths are rejected. Only the model entry is decompressed; Edge Gallery databases, samples and GPU caches are ignored. Search media needs its own scan in this app.

The import worker needs no network connection. It keeps access to the selected document across process restarts, creates a temporary private ZIP copy for providers that cannot seek, and verifies the model’s exact length, SHA-256 and archive CRC while streaming. It requires temporary free storage for the ZIP copy plus the 485 MB model and reserve space. Temporary files are removed on completion, failure or cancellation. Abandoned staging files are reclaimed when a later import begins. A killed worker is retried by WorkManager from the selected document.

Download and import share one unique model-work queue. A verified model is installed with a same-filesystem atomic rename, so a failed import retains the prior model. Existing media/index data is retained. Settings displays copy/verification progress and cancellation. After import, automatic scanning follows the existing enabled/paused settings.

The General settings entry now uses the same row container and label styles as its neighboring settings, preserving their start inset and touch area.

## Debug update compatibility

The earlier debug rebuild was version `1.14.3-ai2`, version code 51. Current release versions and signed beta/stable builds follow [RELEASING.md](RELEASING.md). See `debug-signing/README.md`: it includes a reusable development signing key for future updates. The previous workspace-generated key was removed during environment maintenance. Uninstall the older AI debug build before installing this one for the first time. This does not require removing official Fossify Gallery.

## Copy an AI error report

After an AI failure, open Settings → AI natural language search → **Copy AI error report**.
Preview the report and tap **Copy AI error report** in the dialog, then paste it into your bug report.
The latest failure stays saved across app restarts. Reports include a stable error code,
app version, Android/device details, model verification status, runtime versions and exception
traces, including GPU/CPU fallback causes. Model startup messages retain the native runtime's
reason with paths and URLs removed. Known download/import validation messages are retained. Other messages from operations
that handle media or queries are omitted; exception types and stack frames are retained. Nothing is uploaded automatically.

Codes `AI-1xx` cover model prerequisites, `AI-2xx` model loading, `AI-3xx` inference,
`AI-4xx` download/import and `AI-5xx` scanning/search/index maintenance. The report only covers
caught failures; a native process crash cannot produce a Kotlin exception report.
Release workflows retain the R8 mapping as a private Actions artifact for 90 days so
obfuscated runtime frames can be decoded against the exact release.
