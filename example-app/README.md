# Example App for `@capgo/capacitor-speech-recognition`

This Vite playground links to the plugin via `file:..` so you can validate live speech-to-text flows while iterating on the native code.

## Features

- Display current availability, permission state, and listening status.
- Request permissions with a single tap.
- Configure locale, result count, prompt text, popup usage, and partial streaming.
- View partial transcription events and final results in real time.

## Getting started

```bash
npm install
npm start
```

Need a native shell? From this folder run `npx cap add ios` or `npx cap add android`, then `npx cap sync` once the plugin is rebuilt.
