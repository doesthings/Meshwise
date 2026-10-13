# Contributing

Thanks for helping improve Meshwise! Bug reports, model files that load wrongly, and pull
requests are all welcome.

## Reporting a problem

Open an issue with your device model, Android version, the app version (Settings → About) and,
if a file does not open or looks wrong, the file itself or a link to it when you can share it.

## Development setup

See [Building](README.md#building) and [Testing](README.md#testing) in the README. The fastest
loop for anything in `native/core` is the desktop build: `fsv_tests` for logic and
`fsv_render` to look at rendering changes without a phone.

## Pull requests

- Keep changes focused; one feature or fix per pull request.
- Run `native/build-host/fsv_tests` and `./gradlew :app:testDebugUnitTest` before pushing.
- Add a test in `native/tests/tests.cpp` for loader, geometry or analysis changes.
- UI changes: regenerate the README screenshots with `scripts/make_screenshots.sh` when they
  change what the screenshots show.
- Match the surrounding style: C++17 in `native/`, idiomatic Kotlin and Compose in `app/`.

By contributing you agree that your contributions are licensed under the MIT License.
