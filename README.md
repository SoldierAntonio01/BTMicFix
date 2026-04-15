# BTMicFix

**Fix Bluetooth earbuds microphone routing for AI voice apps on Android.**

AI voice apps like ChatGPT, Claude, and Gemini often fail to switch your Bluetooth earbuds from music mode (A2DP) to hands-free mode (SCO/HFP), causing them to use your phone's built-in microphone instead of your earbuds' mic. BTMicFix forces this switch automatically.

## How It Works

BTMicFix uses Android's `setCommunicationDevice()` API (Android 12+) to force the system to route communication audio through your Bluetooth earbuds. This triggers the A2DP → SCO/HFP profile switch that AI apps fail to perform on their own.

### Three Layers

| Layer | Purpose | Requires |
|---|---|---|
| **Audio Routing** (primary) | Forces BT mic via `setCommunicationDevice()` | Nothing — public API |
| **Background Service** | Auto-activates when earbuds connect via Companion Device Manager | One-time pairing in app |
| **Shizuku Fallback** (optional) | Privileged shell commands for stubborn devices | [Shizuku](https://shizuku.rikka.app/) installed |

## Requirements

- Android 12+ (API 31+)
- Bluetooth earbuds paired with your device
- No root required

## Building

```bash
# Clone the repo
git clone https://github.com/Endda/btmicfix.git
cd btmicfix

# Build the debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. **Install** the APK on your Android device
2. **Grant Bluetooth permission** when prompted
3. **Pair your earbuds** in the Setup wizard (for automatic background mode)
4. **Tap "Enable Routing"** on the home screen
5. **Open your AI app** — your earbuds' microphone should now work

For automatic background mode, complete the pairing step. BTMicFix will then activate whenever your earbuds connect, even without opening the app.

## Shizuku (Optional)

Some devices or Android versions may not respond to the standard `setCommunicationDevice()` API. For these cases, BTMicFix can optionally use [Shizuku](https://shizuku.rikka.app/) to execute privileged audio routing commands. This is **not required** for most users.

## Project Structure

```
app/src/main/java/com/btmicfix/
├── BTMicFixApp.kt              # Application class
├── MainActivity.kt             # Single activity entry point
├── audio/
│   ├── AudioRoutingManager.kt  # Core routing logic (setCommunicationDevice)
│   └── BluetoothStateReceiver.kt # BT connect/disconnect listener
├── companion/
│   ├── DeviceCompanionManager.kt # CDM association management
│   └── BTCompanionService.kt    # Background auto-routing service
├── shizuku/
│   ├── ShizukuManager.kt       # Shizuku lifecycle & permissions
│   └── PrivilegedServiceImpl.kt # Privileged command execution
├── ui/
│   ├── components/             # Reusable Compose components
│   ├── screens/                # HomeScreen, SetupScreen
│   └── theme/                  # Material 3 dark theme
└── util/
    ├── Preferences.kt          # SharedPreferences wrapper
    └── Logger.kt               # Centralized logging
```

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Audio:** AudioManager (API 31+)
- **Background:** CompanionDeviceManager + CompanionDeviceService
- **Optional:** Shizuku API 13.1.5

## Contributing

Contributions are welcome! Please open an issue first to discuss what you'd like to change.

## License

```
Copyright 2026 BTMicFix Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
