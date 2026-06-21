<div align="center">

  <img src="https://github.com/tapframe/NuvioTV/blob/main/assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    A modern media hub for Android and iOS built with Kotlin Multiplatform and Compose Multiplatform.
    <br />
    Stremio addon ecosystem • Cross-platform
  </p>

</div>

## About

Nuvio is the current Kotlin Multiplatform rewrite of the original React Native app. It delivers a shared Compose UI for Android and iOS while keeping the playback-focused experience, collection tools, watch progress flows, downloads, and Stremio addon ecosystem integration that shaped the earlier app.

The mobile app is built from a single shared codebase in [composeApp](./composeApp), with native platform entry points for Android and iOS.

## Installation

### Android

Download the latest Android build from [GitHub Releases](https://github.com/NuvioMedia/NuvioMobile/releases/latest).

### iOS

- [TestFlight](https://testflight.apple.com/join/u4y7MHK9)

## Development

```bash
git clone https://github.com/NuvioMedia/NuvioMobile.git
cd NuvioMobile
./scripts/run-mobile.sh android
# or
./scripts/run-mobile.sh ios
```

### Project Structure

- `composeApp/` contains the shared Kotlin Multiplatform and Compose Multiplatform app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/androidMain/` contains Android-specific integrations.
- `composeApp/src/iosMain/` contains iOS-specific integrations.
- `iosApp/` contains the native Xcode project and iOS entry point.

Useful commands:

```bash
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./scripts/build-distribution.sh
```

### Desktop on Arch Linux

Install the desktop build prerequisites. `firejail` is optional as a fallback IPv4-only sandbox; the local launcher normally uses the bundled no-IPv6 `LD_PRELOAD` library first.

```bash
sudo pacman -S jdk21-openjdk mpv firejail
echo "$JAVA_HOME"
java -version
javac -version
ldd /usr/lib/libmpv.so.2
```

Build the Compose Desktop release app image and Arch-friendly archive:

```bash
./gradlew :composeApp:createReleaseDistributable
./gradlew :composeApp:packageReleaseArch
```

`packageReleaseArch` only runs on Linux hosts and writes `composeApp/build/compose/binaries/main-release/arch/Nuvio-<version>-linux-x64.tar.gz`.
Linux playback prefers the system MPV runtime at `/usr/lib/libmpv.so.2`; set `NUVIO_MPV_DIR=/path/to/libmpv-dir` before launching only when testing a custom MPV build.

For local desktop installs, run:

```bash
bash scripts/install-local-linux.sh
nuvio-linux
```

The local launcher defaults to embedded MPV with `NUVIO_MPV_NETWORK_MODE=direct-ipv4`, JVM IPv4 startup flags, and the bundled no-IPv6 `LD_PRELOAD` library. If that library is missing, it falls back to Firejail when `firejail` is installed. To launch without either IPv4-only process wrapper, use:

```bash
NUVIO_IPV4_ONLY=0 nuvio-linux
```

The local playback proxy is not the default. Use it only for explicit debugging:

```bash
NUVIO_MPV_NETWORK_MODE=proxy nuvio-linux
```

AUR packaging note: add `firejail` as an optional fallback dependency:

```text
firejail: fallback IPv4-only sandbox for reliable direct MPV playback
```

Run the release app image for a runtime smoke test:

```bash
./gradlew :composeApp:runReleaseDistributable
```

Before publishing a Linux archive, manually verify that the app opens, catalog UI loads, one stream starts, the MPV backend initializes, fullscreen works, and there is no missing `libmpv` or native-loading crash.

Versioning is driven from `iosApp/Configuration/Version.xcconfig`, which is used as the shared source of truth for both iOS and Android builds.

## Legal & DMCA

Nuvio functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including our full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit our [Legal & Disclaimer Page](https://nuvioapp.space/legal).

## Built With

- Kotlin Multiplatform
- Compose Multiplatform
- Kotlin
- AndroidX Media3
- AVFoundation and native iOS integrations

## Star History

<a href="https://www.star-history.com/#NuvioMedia/NuvioMobile&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=NuvioMedia/NuvioMobile&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=NuvioMedia/NuvioMobile&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=NuvioMedia/NuvioMobile&type=date&legend=top-left" />
 </picture>
</a>

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/NuvioMedia/NuvioMobile.svg?style=for-the-badge
[contributors-url]: https://github.com/NuvioMedia/NuvioMobile/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/NuvioMedia/NuvioMobile.svg?style=for-the-badge
[forks-url]: https://github.com/NuvioMedia/NuvioMobile/network/members
[stars-shield]: https://img.shields.io/github/stars/NuvioMedia/NuvioMobile.svg?style=for-the-badge
[stars-url]: https://github.com/NuvioMedia/NuvioMobile/stargazers
[issues-shield]: https://img.shields.io/github/issues/NuvioMedia/NuvioMobile.svg?style=for-the-badge
[issues-url]: https://github.com/NuvioMedia/NuvioMobile/issues
[license-shield]: https://img.shields.io/github/license/NuvioMedia/NuvioMobile.svg?style=for-the-badge
[license-url]: https://github.com/NuvioMedia/NuvioMobile/blob/main/LICENSE
