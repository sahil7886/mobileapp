# Pebble Mobile app

This is an iPhone-only, local-first fork of the Pebble mobile app. It retains the Pebble companion, public app/watchface catalog, and watch synchronization paths while omitting Android, Firebase, and the unrelated Ring product.

It supports Pebble watches, including Pebble Time 2.

**Note: this is a public copy of our internal repo, where we do active development; it is synced regularly, but this process is manual so it may lag behind.

# Architecture

**New to Pebble?** A Pebble watch runs its own firmware and its own apps/watchfaces, but has no internet connection of its own. This app is the watch's companion and gateway to the world: it holds a persistent Bluetooth connection (BLE, or Bluetooth Classic on older watches) to relay notifications, sync data (time, weather, calendar, contacts, health), install watchapps, and proxy network requests on the watch's behalf. Much of the app's job is to be a reliable background service that stays connected and answers the watch quickly.

The codebase remains **Kotlin Multiplatform + Compose Multiplatform**, with an iOS Swift shell. The active build graph is iOS-only; Android sources remain in the checkout as inactive historical reference and are not built. Platform-specific pieces (BLE stack, notification access, background execution) sit behind `expect`/`actual` interfaces. On iOS, `iosApp` embeds the shared Kotlin code as a framework via CocoaPods.

The public app/watchface catalog remains network-backed. Installing an item stores it in the local SQLite Locker maintained by `libpebble3`, then the usual Pebble Bluetooth sync installs it on the watch. The personal Locker is deliberately local to this iPhone: it is not backed up or restored through a cloud account.

Watch communication lives in `libpebble3` and follows a few core concepts:

- **Pebble Protocol** — a binary, endpoint-based message protocol spoken over the Bluetooth connection. Packet definitions live in `libpebble3` under `io/rebble/libpebblecommon/packets/`.
- **Services & endpoint managers** — both scoped to a single watch connection. Services translate raw protocol messages into typed APIs for the rest of the app; endpoint managers handle the more complex stateful flows on top of them.
- **BlobDB** — the watch keeps small key-value databases (notifications, timeline pins, installed apps, contacts, …). The phone keeps the canonical copy of each record in a Room database and reconciles with the watch over the protocol (mostly phone → watch, with some watch-originated writebacks). The `blobannotations` + `blobdbgen` modules generate the serialization/sync plumbing via KSP.
- **PebbleKit JS** — watchapps can include a JavaScript component that runs on the *phone*, inside this app (`js/`), giving watchapps network access and configuration UIs.

Module map:

| Module | What it is |
|---|---|
| `composeApp` | App entry point: Compose UI, navigation, and DI wiring (Koin) |
| `libpebble3` | Everything needed to talk to a Pebble watch: BLE transport, protocol, services, BlobDB sync. Also usable as a standalone library |
| `pebble` | Pebble app features shared between platforms, above the library layer |
| `index-ai` | Local data types used by the remaining shared code |
| `libindex` | Compatibility interfaces; the Ring product is disabled in this fork |
| `mcp` | MCP (Model Context Protocol) client/tool integration |
| `cactus`, `resampler`, `krisp-stubs` | Audio/ML support: on-device LLM inference, audio resampling, and API stubs for the private Krisp noise-cancellation integration |
| `blobannotations`, `blobdbgen` | KSP annotations + code generator for BlobDB records |
| `util` | Shared utilities (logging, IO, …) |

Stack at a glance: Koin (DI), Ktor (HTTP), Room (storage), Kermit (logging), coroutines/Flow throughout.

# Mobile App

The cross-platform Pebble mobile app is located in `composeApp`.

Core Pebble features work without a cloud account. Apple sign-in is retained only as a device-local identity marker; it does not create a cloud Locker account. Cloud-only features whose servers require the removed account service (such as support inbox, developer contact, cloud battery dashboard, and cloud transcription) are unavailable in this fork.

### iOS

#### Prerequisites

1. **Install Java 17**

   ```bash
   # Install
   brew install openjdk@17
   
   # Symlink (optional)
   sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk
   
   # Verify installation
   /usr/libexec/java_home -v 17
   ```

2. **Install CocoaPods**

   ```bash
   # Install
   brew install cocoapods
   
   # Symlink (optional)
   sudo ln -s /opt/homebrew/bin/pod /usr/local/bin/pod
   ```

3. **Install the iOS platform for Xcode**

   Recent Xcode versions ship without the iOS platform (SDK device
   support and simulator runtime). Without it, any iOS build fails with
   "iOS X.Y is not installed". Download it with:

   ```bash
   xcodebuild -downloadPlatform iOS
   ```

   (This is the same ~8 GB download as Xcode → Settings → Components.)

#### Configuration

4. **Configure Signing and Capabilities**

   In Xcode, set your development team and a unique bundle identifier, then
   enable **HealthKit**, **Sign in with Apple**, and **Push Notifications** for
   that identifier. The committed entitlement file already declares them.

5. **Install CocoaPods dependencies**

   ```bash
   ./gradlew podInstall
   ```

   This creates a fresh `Podfile.lock` and sets up the local `composeApp` pod.

6. **Create a git tag for app version**

   Create a git tag that will be used as the version of the app:

   ```bash
   git tag 1.0.0
   ```

#### Build and Run

7. **Build and run in Xcode**

   - Open `iosApp/iosApp.xcworkspace` in Xcode
   - Select your target device or simulator and run.

   > **Tip**: If you encounter a module-not-found error, make sure you:
   > - Opened the `.xcworkspace` file (not `.xcodeproj`)
   > - Ran `pod install` successfully
   > - Cleaned the build folder (`Product → Clean Build Folder`)

   > **Known issue**: simulator builds currently fail to link with
   > `ld: building for 'iOS-simulator', but linking in object file (...libspeex.a...) built for 'iOS'`.
   > The published `io.github.coredevices.speex` simulator artifact ships a
   > device-built `libspeex.a` inside its iosSimulatorArm64 klib (every
   > version on Maven Central is affected) and needs to be republished.
   > Until then, run on a physical device. Note the simulator has no
   > Bluetooth in any case, so connecting a watch requires a device.




# Naming your project

In order to honour the Pebble trademark, you may not use "Pebble" in the name of your app, product or service, except in a referential manner. For example, "Awesome App for Pebble" is acceptable, but "Pebble Awesome" is not.

# Contributing

Pebble employs several (extremely busy) full time mobile developers to work on this app. If you'd like to contribute, we welcome PRs but caution you that it may take us some time before we can review your PR. Please be patient with us :)

# Reporting bugs

Use the local diagnostic export in the app, or open a GitHub issue for this fork. The legacy cloud support inbox is intentionally disabled.

# Development Guidelines

(We don't follow all of these everywhere, yet, and need to document a lot more..)

- We share a version catalog with CoreApp to avoid duplicating definitions. This means a few extra library entries which are not used in libpebble (so they can share the version definition).
- Use `optIn` in `build.gradle.kts` rather than individual source files.
- Only use injected coroutine scopes: either LibPebbleCoroutineScope (instead of GlobalScope) or ConnectionCoroutineScope (scoped per-connection).

Connection:
- Services are scoped to the connection. Their main job is to translate raw pebble protocol messages to something readable by the rest of the app.
- Endpoint managers are also scoped to the connection, and manage complex state around services.

# Copyright and Licensing

See https://ericmigi.notion.site/Core-Devices-Software-Licensing-1c0fbb55ea8480f88d27ccf20fcb84a8

Copyright 2026 Core Devices LLC

This software is dual-licensed by Core Devices LLC. It can be used either:
  
(1) for free under the terms of the GNU GPLv3; OR
  
(2) under the terms of a paid-for Core Devices Commercial License agreement between you and Core Devices (the terms of which may vary depending on what you and Core Devices have agreed to).

Unless required by applicable law or agreed to in writing, software distributed under the Licenses is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the Licenses for the specific language governing permissions and limitations under the Licenses.

Additional Permissions For Submission to Apple App Store: Provided that you are otherwise in compliance with the GPLv3 for each covered work you convey (including without limitation making the Corresponding Source available in compliance with Section 6 of the GPLv3), Core Devices also grants you the additional permission to convey through the Apple App Store non-source executable versions of the Program as incorporated into each applicable covered work as Executable Versions only under the Mozilla Public License version 2.0 (https://www.mozilla.org/en-US/MPL/2.0/).
