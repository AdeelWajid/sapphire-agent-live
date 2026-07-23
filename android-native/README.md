# Plano Agent — Native Android (Kotlin)

Talk-to-agent only. Same UI as before; wired to the Plano Agent complaints backend.

## Features

- Home + call UI (unchanged layout)
- No retailer database login — opens straight to home
- Live voice over WebSocket (`/api/live`) with **Urdu-only** session
- Clothing product picker (`/api/products` / `show_products`)
- Floating call bubble, PTT default, farewell / `end_call` hang-up

## Requirements

- Android Studio + JDK 17
- Device / emulator API **26+**
- Mic permission
- Plano Agent server running (`npm run dev` in repo root)

## Point at the server

Defaults (debug + release):

- `LIVE_WS_URL` = `wss://sapphire.adaxiomtech.com/api/live`
- `API_BASE_URL` = `https://sapphire.adaxiomtech.com`

Override in `app/build.gradle.kts` if you need a local server.

## Run

```bash
cd android-native
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```
