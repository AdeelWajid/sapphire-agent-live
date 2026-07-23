# Adaxiom AI Android WebView

Fullscreen Kotlin Android wrapper for the Plano Agent web experience at [voiceassistant.adaxiomtech.com](https://voiceassistant.adaxiomtech.com/).

## Open and run

1. Open the `android-webview` folder in Android Studio.
2. Let Android Studio install/sync the Android Gradle Plugin dependencies.
3. Select an Android device or emulator and run the `app` configuration.

The app keeps trusted Adaxiom pages inside the fullscreen WebView, opens external URLs in the device browser, supports WebView audio capture, and requests the device microphone permission on launch and again when the site asks to use it.

Trusted hosts for in-app browsing + mic capture include `*.adaxiomtech.com` (e.g. `voiceassistant.adaxiomtech.com`, `markme.adaxiomtech.com`).
