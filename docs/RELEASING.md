# Releasing

Releases are built by [`.github/workflows/release.yml`](../.github/workflows/release.yml).
Pushing a tag that starts with `v` builds Open CASCADE for every ABI (cached after the first
run), assembles the release APKs and publishes them, with SHA-256 checksums, on the
repository's **Releases** page.

```bash
# 1. bump the changelog and add docs/release-notes/vX.Y.Z.md (used as the release body)
# 2. tag and push
git tag -a v1.1.0 -m "Meshwise 1.1.0"
git push origin v1.1.0
```

Alternatively run the **Release** workflow from the Actions tab ("Run workflow"), pick the
branch and enter the tag name; the tag is created at that branch's head when the release
is published.

The version name comes from the tag (`v1.1.0` → `1.1.0`) and the version code is derived from
it (`major*10000 + minor*100 + patch`, times ten plus an ABI digit for the split APKs), so
every release installs over the previous one.

## Signing key (one-time setup)

Without signing secrets the workflow still produces installable APKs, but signs them with a
throwaway key, so Android will not update one release with the next (users must uninstall
first). Set up a permanent key once:

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
means users cannot update, and leaking it lets anyone publish updates in your name.

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
