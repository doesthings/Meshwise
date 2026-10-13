# Contributing

Thanks for helping improve Meshwise! Bug reports, files that load wrongly, and pull requests
are all welcome.

## Reporting a problem

[Open an issue](../../issues/new/choose) and include:

- your phone or tablet model and Android version
- the app version (Settings → About)
- if a file doesn't open or looks wrong: the file, or a link to it, if you can share it

## Development setup

See [Building](README.md#building) and [Testing](README.md#testing) in the README.

For anything in `native/core`, the desktop build is the fastest loop: `fsv_tests` checks the
logic and `fsv_render` shows rendering changes without a phone.

## Pull requests

- Keep each pull request to one feature or fix.
- Run `native/build-host/fsv_tests` and `./gradlew :app:testDebugUnitTest` before you push.
- Add a test in `native/tests/tests.cpp` for loader, geometry or analysis changes.
- If a UI change affects what the README screenshots show, regenerate them with
  `scripts/make_screenshots.sh`.
- Match the surrounding style: C++17 in `native/`, idiomatic Kotlin and Compose in `app/`.

By contributing, you agree that your contributions are licensed under the MIT License.
