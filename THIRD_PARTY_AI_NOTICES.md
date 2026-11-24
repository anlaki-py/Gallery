# AI search third-party notices

Fossify Gallery retains its existing GPL license and copyright notices.

`AiEmbeddingEngine.kt` adapts the Universal Embedder setup and GPU/CPU fallback approach from Google AI Edge Gallery:

- Copyright 2026 Google LLC
- Apache License, Version 2.0
- https://github.com/google-ai-edge/gallery
- Reference revision: eb832659e79c42629cee202b103717c91adf51b4
- Reference files: services/semanticretrieval/OnDeviceEmbedder.kt; customtasks/smartalbum/SmartAlbumViewModel.kt; model_allowlists/1_0_23.json

The adapted wrapper removes unrelated audio, NPU, model catalog and UI integration, and uses one shared native thread with bounded image decoding, per-frame video indexing, idle cleanup and explicit error propagation. The license text is included as `licenses/AI_EDGE_APACHE_2_0.txt`.

MediaPipe Tasks Retrieval and LiteRT-LM are provided by Google under their respective packaged licenses and notices. Their AAR notices remain in the dependencies/APK. The on-demand EmbeddingGemma 2 model is not redistributed in this package. Its pinned Hugging Face repository declares Apache-2.0; consult the model repository for the model card and notices.
