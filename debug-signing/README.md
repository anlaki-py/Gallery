# Reusable debug signing key

This key is for development APKs only. The debug build uses it automatically.

- Keystore: debug.keystore
- Alias: androiddebugkey
- Store/key password: android

Keep this exact file when rebuilding so subsequent debug APKs can update this build without uninstalling it. It is included in the source archive for that purpose. Do not use this publicly distributed debug key for production releases.

The previous build's machine-generated debug key was removed by workspace maintenance and was not present in the original source archive. This build therefore has a new debug signing certificate. Uninstall the previous AI debug app before the first installation of this build. The official Fossify app uses a different application ID and is unaffected.
