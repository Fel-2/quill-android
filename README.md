# Quill Android

Native Android companion for the Noctalia Quill plugin. The app is written in Kotlin with Jetpack Compose and Material 3.

## Requirements

- Android Studio with JDK 17
- Android SDK 35
- A reachable PC bridge from `../bridge`
- The same LAN, or a VPN if the PC is not on the local network

Gradle also needs the Android SDK. Set `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or create an ignored `local.properties` file:

```properties
sdk.dir=/path/to/android-sdk
```

The app does not import the desktop plugin's runtime. The bridge exposes the notes directory as conflict-safe Markdown files, while the app parses and edits the same format locally.

## Build

If the shell defaults to a newer JDK, select JDK 17 first:

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./gradlew --stop
./gradlew assembleDebug
```

The same JDK can be selected in Android Studio under **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**.

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run unit tests with:

```sh
./gradlew testDebugUnitTest
```

These are JVM tests, so `NotesRepository` (which needs an Android `Context`) is
not covered; only the parser and the offline outbox are.

## Pairing

1. Build and start the bridge on the PC:

   ```sh
   cd ../bridge
   go build -o quill-bridge .
   ./quill-bridge --notes ~/notes
   ```

2. Open Quill on Android and choose **Find bridge on this network**.
3. Enter the short pairing code shown by the bridge, or open its printed `quill://pair?...` URI on the phone.
4. The app verifies and pins the bridge certificate. Later connections use the saved device token.

The bridge must be restarted to generate a new initial pairing code. An already paired device can use the authenticated rotation endpoint when adding another device. Release builds require the bridge fingerprint; debug builds also expose the bridge's explicit HTTP development mode.

## Offline and multiple devices

Once paired, the app can be opened with the PC offline. Edits are written to the phone immediately and placed in a durable outbox; the top bar shows `offline`, `pending`, or `conflicts`. The app retries automatically when the bridge returns. A stale desktop edit is never silently overwritten.

The bridge supports several active pairing codes and per-device tokens. Use **Settings → Create code for another device** to pair a second phone, laptop mirror, or desktop mirror. The topology is hub-and-spoke: one bridge owns the canonical notes directory, while the other devices keep local copies and synchronize through it.


- LAN discovery and PairDrop-style short-code pairing
- Multiple paired devices through one bridge hub
- Pinned TLS and Android Keystore-backed token storage
- Local Markdown cache with a durable offline outbox
- Automatic reconnect sync and conflict preservation
- ETag conflict detection for desktop/mobile edits
- Todos, due dates, recurrence, priorities, tags, filters, archive, and undo
- Calendar tab: month grid of due todos by date and time
- Notes, search, Markdown editor/preview, Today notes, create/edit/delete
- Android share-sheet capture
- Due-date notifications that fire at the todo's time, not on a 15-minute poll
- AI capture, summaries, extraction, rewriting, Q&A, planning, and weekly review
- OpenAI-compatible, OpenAI, Anthropic, Google, OpenRouter, Groq, Ollama, and OpenCode Go providers

The app can also encrypt its local note cache (Settings → Local cache). The
Markdown files the bridge serves stay plain; only this device's copy is wrapped
with an Android Keystore key, so a lost phone does not expose note contents.
The offline outbox is encrypted with the same setting.

The Android app can create a new pairing code from **Settings → Create code for another device**. The PC bridge can also run its `mirror` mode on a second computer, allowing laptop, desktop, and phone to share the same Markdown hub.

The bridge deliberately does not receive AI API keys. AI calls originate on Android, while only Markdown files and device authentication data cross the bridge. The desktop-only `opencode CLI` provider is not available on Android; use OpenCode Go or another HTTP provider there.
