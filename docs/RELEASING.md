# Releasing

Releases are built by [`.github/workflows/release.yml`](../.github/workflows/release.yml).
It builds Open CASCADE for every ABI (cached after the first run), assembles the release APKs,
checks that they are signed with the Meshwise key and publishes them, with SHA-256 checksums,
on the repository's **Releases** page.

To publish a new version:

1. Update `CHANGELOG.md` and add `docs/release-notes/vX.Y.Z.md` (it becomes the release text).
2. Change the version in the `VERSION` file and push to `main`.

The **Release** workflow notices the new version, builds and verifies the APKs and publishes
release `vX.Y.Z` (creating the tag). Pushing a `vX.Y.Z` tag or running the workflow by hand
from the Actions tab works too.

The version code is derived from the version (`major*10000 + minor*100 + patch`, times ten
plus an ABI digit for the split APKs), so every release installs over the previous one.

## Signing key (one-time setup)

A release is only published when every APK is signed with the Meshwise release key: the
workflow checks the certificate against `EXPECTED_CERT_SHA256` in `release.yml` and fails
otherwise, including when the signing secrets are missing. For a quick unsigned check, run the
workflow manually with **Test build** ticked; the APKs are then attached to the run as an
artifact and nothing is published.

The project's key already exists (owner's private backup). To create a key for a fork:

```bash
keytool -genkeypair -v -keystore release.jks -alias meshwise \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks > release.jks.b64
```

Then add these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | contents of `release.jks.b64` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `meshwise` (or the alias you chose) |
| `SIGNING_KEY_PASSWORD` | key password |

Keep `release.jks` and its passwords somewhere safe and **never commit them**: losing the key
means users cannot update, and leaking it lets anyone publish updates in your name. A fork
with its own key must also update `EXPECTED_CERT_SHA256` in `release.yml`.

## Local release builds

Create `keystore.properties` in the repository root (it is git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=meshwise
keyPassword=...
```

and run `./gradlew assembleRelease -Pfsv.versionName=1.1.0`. Without the file the release
build falls back to the debug key.
