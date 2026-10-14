# Releasing

The [Release workflow](../.github/workflows/release.yml) does all the work:

1. builds Open CASCADE for every ABI (cached after the first run),
2. builds the release APKs,
3. checks that every APK is signed with the Meshwise key,
4. publishes the APKs and `SHA256SUMS.txt` on the **Releases** page.

## Publish a new version

1. Add a `CHANGELOG.md` entry and `docs/release-notes/vX.Y.Z.md` (this becomes the release text).
2. Put the new version in the `VERSION` file.
3. Push to `main`.

The workflow sees the new version and publishes release `vX.Y.Z`, creating the tag. If that tag
already exists, it does nothing. Pushing a `vX.Y.Z` tag, or running the workflow by hand from
the Actions tab, also works.

The version code comes from the version (`major*10000 + minor*100 + patch`, times ten, plus an
ABI digit for the split APKs), so each release installs over the previous one.

## Version numbers

`MAJOR.MINOR.PATCH`, raised as little as the change allows:

- **Patch** (1.3.0 → 1.3.1) for most releases: improvements, better accuracy, new options in an
  existing tool, UI polish and fixes.
- **Minor** (1.3 → 1.4) only when a release adds a whole new tool or a new kind of file.
  Several patch releases usually come in between.
- **Major** (1 → 2) for a redesign, or a change that old project files can't follow.

Collect changes and release when there is something worth updating for, not after every
commit; urgent fixes can go out on their own. The patch number can go up to 99 (it is part of
the version code).

## Signing key (one-time setup)

A release is only published when every APK is signed with the Meshwise release key. The
workflow compares each certificate with `EXPECTED_CERT_SHA256` in `release.yml` and fails if
any differs, or if the signing secrets are missing.

To try a build without publishing, run the workflow by hand with **Test build** ticked. The APKs
are then attached to the run as an artifact.

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

Keep `release.jks` and its passwords somewhere safe and **never commit them**. If you lose the
key, users can't update. If it leaks, anyone can publish updates in your name. A fork with its
own key must also change `EXPECTED_CERT_SHA256` in `release.yml`.

## Local release builds

Create `keystore.properties` in the repository root (it is git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=meshwise
keyPassword=...
```

Then run `./gradlew assembleRelease`. Without the file, the release build uses the debug key.
