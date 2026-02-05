# @capgo/capacitor-speech-recognition
 <a href="https://capgo.app/"><img src='https://raw.githubusercontent.com/Cap-go/capgo/main/assets/capgo_banner.png' alt='Capgo - Instant updates for capacitor'/></a>

<div align="center">
  <h2><a href="https://capgo.app/?ref=plugin_speech_recognition"> ➡️ Get Instant updates for your App with Capgo</a></h2>
  <h2><a href="https://capgo.app/consulting/?ref=plugin_speech_recognition"> Missing a feature? We’ll build the plugin for you 💪</a></h2>
</div>

Natural, low-latency speech recognition for Capacitor apps with parity across iOS and Android, streaming partial results, and permission helpers baked in.

## Why this plugin?

This package starts from the excellent [`capacitor-community/speech-recognition`](https://github.com/capacitor-community/speech-recognition) plugin, but folds in the most requested pull requests from that repo (punctuation support, segmented sessions, crash fixes) and keeps them maintained under the Capgo umbrella. You get the familiar API plus:

- ✅ **Merged community PRs** – punctuation toggles on iOS (PR #74), segmented results & silence handling on Android (PR #104), and the `recognitionRequest` safety fix (PR #105) ship out-of-the-box.
- 🚀 **New Capgo features** – configurable silence windows, streaming segment listeners, consistent permission helpers, and a refreshed example app.
- 🛠️ **Active maintenance** – same conventions as all Capgo plugins (SPM, Podspec, workflows, example app) so it tracks Capacitor major versions without bit-rot.
- 📦 **Drop-in migration** – TypeScript definitions remain compatible with the community plugin while exposing the extra options (`addPunctuation`, `allowForSilence`, `segmentResults`, etc.).

## Documentation

The most complete doc is available here: https://capgo.app/docs/plugins/speech-recognition/

## Compatibility

| Plugin version | Capacitor compatibility | Maintained |
| -------------- | ----------------------- | ---------- |
| v8.\*.\*       | v8.\*.\*                | ✅          |
| v7.\*.\*       | v7.\*.\*                | On demand   |
| v6.\*.\*       | v6.\*.\*                | ❌          |
| v5.\*.\*       | v5.\*.\*                | ❌          |

> **Note:** The major version of this plugin follows the major version of Capacitor. Use the version that matches your Capacitor installation (e.g., plugin v8 for Capacitor 8). Only the latest major version is actively maintained.

## Install

```bash
npm install @capgo/capacitor-speech-recognition
npx cap sync
```

## Usage

```ts
import { SpeechRecognition } from '@capgo/capacitor-speech-recognition';

await SpeechRecognition.requestPermissions();

const { available } = await SpeechRecognition.available();
if (!available) {
  console.warn('Speech recognition is not supported on this device.');
}

const partialListener = await SpeechRecognition.addListener('partialResults', (event) => {
  console.log('Partial:', event.matches?.[0]);
});

await SpeechRecognition.start({
  language: 'en-US',
  maxResults: 3,
  partialResults: true,
});

// Later, when you want to stop listening
await SpeechRecognition.stop();
await partialListener.remove();
```

### iOS usage descriptions

Add the following keys to your app `Info.plist`:

- `NSSpeechRecognitionUsageDescription`
- `NSMicrophoneUsageDescription`

## API

<docgen-index>

* [`available()`](#available)
* [`start(...)`](#start)
* [`stop()`](#stop)
* [`forceStop(...)`](#forcestop)
* [`getLastPartialResult()`](#getlastpartialresult)
* [`setPTTState(...)`](#setpttstate)
* [`getSupportedLanguages()`](#getsupportedlanguages)
* [`isListening()`](#islistening)
* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions()`](#requestpermissions)
* [`getPluginVersion()`](#getpluginversion)
* [`addListener('endOfSegmentedSession', ...)`](#addlistenerendofsegmentedsession-)
* [`addListener('segmentResults', ...)`](#addlistenersegmentresults-)
* [`addListener('partialResults', ...)`](#addlistenerpartialresults-)
* [`addListener('listeningState', ...)`](#addlistenerlisteningstate-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### available()

```typescript
available() => Promise<SpeechRecognitionAvailability>
```

Checks whether the native speech recognition service is usable on the current device.

**Returns:** <code>Promise&lt;<a href="#speechrecognitionavailability">SpeechRecognitionAvailability</a>&gt;</code>

--------------------


### start(...)

```typescript
start(options?: SpeechRecognitionStartOptions | undefined) => Promise<SpeechRecognitionMatches>
```

Begins capturing audio and transcribing speech.

When `partialResults` is `true`, the returned promise resolves immediately and updates are
streamed through the `partialResults` listener until {@link stop} is called.

| Param         | Type                                                                                    |
| ------------- | --------------------------------------------------------------------------------------- |
| **`options`** | <code><a href="#speechrecognitionstartoptions">SpeechRecognitionStartOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#speechrecognitionmatches">SpeechRecognitionMatches</a>&gt;</code>

--------------------


### stop()

```typescript
stop() => Promise<void>
```

Stops listening and tears down native resources.

--------------------


### forceStop(...)

```typescript
forceStop(options?: ForceStopOptions | undefined) => Promise<void>
```

Force stops recognition with a timeout fallback.

This is useful for Push-to-Talk (PTT) implementations where you need
reliable stopping behavior. The Android SpeechRecognizer.stopListening()
doesn't always stop audio capture reliably.

This method:
1. Tries graceful stopListening() first
2. After timeout, forces stop by destroying and recreating the recognizer
3. Emits the last cached partial result via the `partialResults` event

To retrieve the last transcription after force stop, use {@link getLastPartialResult}.

| Param         | Type                                                          | Description                      |
| ------------- | ------------------------------------------------------------- | -------------------------------- |
| **`options`** | <code><a href="#forcestopoptions">ForceStopOptions</a></code> | - Optional timeout configuration |

--------------------


### getLastPartialResult()

```typescript
getLastPartialResult() => Promise<LastPartialResult>
```

Gets the last cached partial transcription result.

Useful for retrieving what was heard before a force stop,
or checking the current partial state at any time.

**Returns:** <code>Promise&lt;<a href="#lastpartialresult">LastPartialResult</a>&gt;</code>

--------------------


### setPTTState(...)

```typescript
setPTTState(options: PTTStateOptions) => Promise<void>
```

EXPERIMENTAL: Set PTT button state for continuous PTT mode.

When continuousPTT is enabled in start() and held is true,
recognition will auto-restart on silence, accumulating results.
Call with held=false when the button is released.

| Param         | Type                                                        | Description         |
| ------------- | ----------------------------------------------------------- | ------------------- |
| **`options`** | <code><a href="#pttstateoptions">PTTStateOptions</a></code> | - PTT state options |

--------------------


### getSupportedLanguages()

```typescript
getSupportedLanguages() => Promise<SpeechRecognitionLanguages>
```

Gets the locales supported by the underlying recognizer.

Android 13+ devices no longer expose this list; in that case `languages` is empty.

**Returns:** <code>Promise&lt;<a href="#speechrecognitionlanguages">SpeechRecognitionLanguages</a>&gt;</code>

--------------------


### isListening()

```typescript
isListening() => Promise<SpeechRecognitionListening>
```

Returns whether the plugin is actively listening for speech.

**Returns:** <code>Promise&lt;<a href="#speechrecognitionlistening">SpeechRecognitionListening</a>&gt;</code>

--------------------


### checkPermissions()

```typescript
checkPermissions() => Promise<SpeechRecognitionPermissionStatus>
```

Gets the current permission state.

**Returns:** <code>Promise&lt;<a href="#speechrecognitionpermissionstatus">SpeechRecognitionPermissionStatus</a>&gt;</code>

--------------------


### requestPermissions()

```typescript
requestPermissions() => Promise<SpeechRecognitionPermissionStatus>
```

Requests the microphone + speech recognition permissions.

**Returns:** <code>Promise&lt;<a href="#speechrecognitionpermissionstatus">SpeechRecognitionPermissionStatus</a>&gt;</code>

--------------------


### getPluginVersion()

```typescript
getPluginVersion() => Promise<{ version: string; }>
```

Returns the native plugin version bundled with this package.

Useful when reporting issues to confirm that native and JS versions match.

**Returns:** <code>Promise&lt;{ version: string; }&gt;</code>

--------------------


### addListener('endOfSegmentedSession', ...)

```typescript
addListener(eventName: 'endOfSegmentedSession', listenerFunc: () => void) => Promise<PluginListenerHandle>
```

Listen for segmented session completion events (Android only).

| Param              | Type                                 |
| ------------------ | ------------------------------------ |
| **`eventName`**    | <code>'endOfSegmentedSession'</code> |
| **`listenerFunc`** | <code>() =&gt; void</code>           |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('segmentResults', ...)

```typescript
addListener(eventName: 'segmentResults', listenerFunc: (event: SpeechRecognitionSegmentResultEvent) => void) => Promise<PluginListenerHandle>
```

Listen for segmented recognition results (Android only).

| Param              | Type                                                                                                                    |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'segmentResults'</code>                                                                                           |
| **`listenerFunc`** | <code>(event: <a href="#speechrecognitionsegmentresultevent">SpeechRecognitionSegmentResultEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('partialResults', ...)

```typescript
addListener(eventName: 'partialResults', listenerFunc: (event: SpeechRecognitionPartialResultEvent) => void) => Promise<PluginListenerHandle>
```

Listen for partial transcription updates emitted while `partialResults` is enabled.

| Param              | Type                                                                                                                    |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'partialResults'</code>                                                                                           |
| **`listenerFunc`** | <code>(event: <a href="#speechrecognitionpartialresultevent">SpeechRecognitionPartialResultEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('listeningState', ...)

```typescript
addListener(eventName: 'listeningState', listenerFunc: (event: SpeechRecognitionListeningEvent) => void) => Promise<PluginListenerHandle>
```

Listen for changes to the native listening state.

| Param              | Type                                                                                                            |
| ------------------ | --------------------------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'listeningState'</code>                                                                                   |
| **`listenerFunc`** | <code>(event: <a href="#speechrecognitionlisteningevent">SpeechRecognitionListeningEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

Removes every registered listener.

--------------------


### Interfaces


#### SpeechRecognitionAvailability

| Prop            | Type                 |
| --------------- | -------------------- |
| **`available`** | <code>boolean</code> |


#### SpeechRecognitionMatches

| Prop          | Type                  |
| ------------- | --------------------- |
| **`matches`** | <code>string[]</code> |


#### SpeechRecognitionStartOptions

Configure how the recognizer behaves when calling {@link SpeechRecognitionPlugin.start}.

| Prop                  | Type                 | Description                                                                                                                                                                                                    |
| --------------------- | -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`language`**        | <code>string</code>  | Locale identifier such as `en-US`. When omitted the device language is used.                                                                                                                                   |
| **`maxResults`**      | <code>number</code>  | Maximum number of final matches returned by native APIs. Defaults to `5`.                                                                                                                                      |
| **`prompt`**          | <code>string</code>  | Prompt message shown inside the Android system dialog (ignored on iOS).                                                                                                                                        |
| **`popup`**           | <code>boolean</code> | When `true`, Android shows the OS speech dialog instead of running inline recognition. Defaults to `false`.                                                                                                    |
| **`partialResults`**  | <code>boolean</code> | Emits partial transcription updates through the `partialResults` listener while audio is captured.                                                                                                             |
| **`addPunctuation`**  | <code>boolean</code> | Enables native punctuation handling where supported (iOS 16+).                                                                                                                                                 |
| **`allowForSilence`** | <code>number</code>  | Allow a number of milliseconds of silence before splitting the recognition session into segments. Required to be greater than zero and currently supported on Android only.                                    |
| **`continuousPTT`**   | <code>boolean</code> | EXPERIMENTAL: Enable continuous PTT mode. When enabled and used with setPTTState(), recognition will auto-restart on silence while the PTT button is held, accumulating results across restarts. Android only. |


#### ForceStopOptions

Options for the forceStop method.

| Prop          | Type                | Description                                                                           |
| ------------- | ------------------- | ------------------------------------------------------------------------------------- |
| **`timeout`** | <code>number</code> | Timeout in milliseconds before forcing stop via destroy/recreate. Defaults to 1500ms. |


#### LastPartialResult

Result from getLastPartialResult.

| Prop            | Type                  | Description                            |
| --------------- | --------------------- | -------------------------------------- |
| **`available`** | <code>boolean</code>  | Whether a partial result is available. |
| **`text`**      | <code>string</code>   | The last partial transcription text.   |
| **`matches`**   | <code>string[]</code> | All partial match alternatives.        |


#### PTTStateOptions

Options for setPTTState method.

| Prop       | Type                 | Description                                                                                     |
| ---------- | -------------------- | ----------------------------------------------------------------------------------------------- |
| **`held`** | <code>boolean</code> | Whether the PTT button is currently held. Set to true on button press, false on button release. |


#### SpeechRecognitionLanguages

| Prop            | Type                  |
| --------------- | --------------------- |
| **`languages`** | <code>string[]</code> |


#### SpeechRecognitionListening

| Prop            | Type                 |
| --------------- | -------------------- |
| **`listening`** | <code>boolean</code> |


#### SpeechRecognitionPermissionStatus

Permission map returned by `checkPermissions` and `requestPermissions`.

On Android the state maps to the `RECORD_AUDIO` permission.
On iOS it combines speech recognition plus microphone permission.

| Prop                    | Type                                                        |
| ----------------------- | ----------------------------------------------------------- |
| **`speechRecognition`** | <code><a href="#permissionstate">PermissionState</a></code> |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### SpeechRecognitionSegmentResultEvent

Raised whenever a segmented result is produced (Android only).

| Prop          | Type                  |
| ------------- | --------------------- |
| **`matches`** | <code>string[]</code> |


#### SpeechRecognitionPartialResultEvent

Raised whenever a partial transcription is produced.

| Prop          | Type                  |
| ------------- | --------------------- |
| **`matches`** | <code>string[]</code> |


#### SpeechRecognitionListeningEvent

Raised when the listening state changes.

| Prop         | Type                                |
| ------------ | ----------------------------------- |
| **`status`** | <code>'stopped' \| 'started'</code> |


### Type Aliases


#### PermissionState

<code>"denied" | "granted" | "prompt"</code>

</docgen-api>
