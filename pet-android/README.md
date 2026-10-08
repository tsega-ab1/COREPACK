# CorePack Pet (Android)

A pet that floats over other apps; tap it to chat. It talks to the COREPACK gateway on your PC
over the USB cable. See `../docs/PHONE_PET.md` for the overview.

Plain Android views, no extra libraries. Package `com.corepack.pet`, minSdk 26, targetSdk 34.

## Build (command line, no Android Studio)

Needs JDK 17, the Android SDK (platform 35, build-tools 36) and Gradle 8.14.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties    # not committed
gradle assembleDebug
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio also works: open this folder as a project.

## Use

1. On the PC: `corepack gateway start` (prints a token, links the phone with `adb reverse`).
2. In the app: paste the token, tap **Save and test connection** (`Connected. Model: ...`).
3. Tap **Allow drawing over other apps**, then **Start the pet**. Drag it; tap it to chat.

If the pet shows an `x`, the gateway is not reachable: check it is running and re-run
`adb reverse tcp:8787 tcp:8787`.
