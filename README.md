# Cleaner

Private, fully offline Android phone cleaner (Night style). No internet permission.

- Home: storage bar and one-tap scan
- Duplicates: exact matches by content (size, quick hash, SHA-256); keeps the original by folder, then oldest date
- Junk: old downloads, APK installers, large videos, thumbnail caches, empty folders
- Trash: removed files go to a hidden trash folder and are recoverable for 7/14/30 days
- Deep Clean (wireless debugging): planned

Builds: push to `main` runs the GitHub Action and attaches a signed `Cleaner.apk` to a release.
Secrets needed: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`.
