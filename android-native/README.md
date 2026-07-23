# Sapphire Agent — Native Android (Kotlin)

Talk-to-agent only. Same UI as before; wired to the Sapphire Agent complaints backend.

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
- Sapphire Agent server running (`npm run dev` in repo root)

## Point at the server

Debug defaults (emulator → host):

- `LIVE_WS_URL` = `ws://10.0.2.2:3000/api/live`
- `API_BASE_URL` = `http://10.0.2.2:3000`

Physical device: set your LAN IP in `app/build.gradle.kts`.

## Run

```bash
cd android-native
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```
