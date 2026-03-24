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
bun add @capgo/capacitor-speech-recognition
bunx cap sync
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

## On-device recognition mode

This plugin now supports an opt-in on-device recognition path behind the explicit
`useOnDeviceRecognition` flag.

### What it is

The default path keeps the long-standing recognizer flow for backward compatibility.
`useOnDeviceRecognition` switches to a newer local speech pipeline when the platform supports it:

- On iOS 26+, it uses Apple's `SpeechAnalyzer` / `SpeechTranscriber` stack.
- On recent Android versions, it uses the on-device `SpeechRecognizer` path.

### Why you might want it

- Better alignment with the latest native speech APIs.
- Improved on-device model handling on supported platforms.
- A cleaner rollout path if you want to adopt newer speech stacks without changing every user immediately.

### Why it is opt-in

Even when a new stack is technically available, changing recognition behavior silently can affect:

- transcript wording
- punctuation behavior
- partial-result timing
- product metrics and user expectations

That is why the plugin keeps the legacy recognizer by default and requires an explicit flag for the new path.

### Recommended rollout

1. Check generic speech support with `available()`.
2. Check the on-device path with `isOnDeviceRecognitionAvailable()`.
3. Enable `useOnDeviceRecognition` only when that second check returns `true`.
4. Roll it out gradually if your app depends on stable transcripts or analytics.

### Example

```ts
import { SpeechRecognition } from '@capgo/capacitor-speech-recognition';

await SpeechRecognition.requestPermissions();

const { available } = await SpeechRecognition.available();
if (!available) {
  throw new Error('Speech recognition is not available on this device.');
}

const { available: onDeviceRecognitionAvailable } =
  await SpeechRecognition.isOnDeviceRecognitionAvailable({
    language: 'en-US',
  });

await SpeechRecognition.start({
  language: 'en-US',
  partialResults: true,
  useOnDeviceRecognition: onDeviceRecognitionAvailable,
});
```

### When not to use it yet

Stay on the default path if:

- you need unchanged behavior for existing users
- you have not validated transcripts for your target locale
- you want identical production behavior across older and newer OS versions

### Platform notes

- iOS uses the newer on-device path only on iOS 26+ and only for locales Apple exposes through the newer speech stack.
- Android uses the on-device recognizer only in inline mode. `popup: true` keeps using the system dialog and is not compatible with `useOnDeviceRecognition`.
- On Android, a supported on-device language may require a model download before recognition can begin.

### iOS usage descriptions

Add the following keys to your app `Info.plist`:

- `NSSpeechRecognitionUsageDescription`
- `NSMicrophoneUsageDescription`

## API

<docgen-index>

* [`available()`](#available)
* [`isOnDeviceRecognitionAvailable(...)`](#isondevicerecognitionavailable)
* [`start(...)`](#start)
* [`stop()`](#stop)
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


### isOnDeviceRecognitionAvailable(...)

```typescript
isOnDeviceRecognitionAvailable(options?: Pick<SpeechRecognitionStartOptions, "language"> | undefined) => Promise<SpeechRecognitionAvailability>
```

Checks whether the platform's newer on-device recognition path is available for the selected locale.

This is the capability check you should use before enabling `useOnDeviceRecognition`.
A `true` result means the current device, OS version, and locale can use the newer
on-device path for that platform.

Returns `false` when the device only supports the legacy recognizer path.

Platform SDK docs:
iOS: [Speech](https://developer.apple.com/documentation/speech)
Android: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)

| Param         | Type                                                                                                                                |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| **`options`** | <code><a href="#pick">Pick</a>&lt;<a href="#speechrecognitionstartoptions">SpeechRecognitionStartOptions</a>, 'language'&gt;</code> |

**Returns:** <code>Promise&lt;<a href="#speechrecognitionavailability">SpeechRecognitionAvailability</a>&gt;</code>

--------------------


### start(...)

```typescript
start(options?: SpeechRecognitionStartOptions | undefined) => Promise<SpeechRecognitionMatches>
```

Begins capturing audio and transcribing speech.

When `partialResults` is `true`, the returned promise resolves immediately and updates are
streamed through the `partialResults` listener until {@link stop} is called.

The default path keeps the legacy recognizer behavior for backward compatibility.
Pass `useOnDeviceRecognition: true` only after checking
{@link SpeechRecognitionPlugin.isOnDeviceRecognitionAvailable}.

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


#### SpeechRecognitionStartOptions

Configure how the recognizer behaves when calling {@link SpeechRecognitionPlugin.start}.

| Prop                         | Type                 | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| ---------------------------- | -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`language`**               | <code>string</code>  | Locale identifier such as `en-US`. When omitted the device language is used.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| **`maxResults`**             | <code>number</code>  | Maximum number of final matches returned by native APIs. Defaults to `5`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| **`prompt`**                 | <code>string</code>  | Prompt message shown inside the Android system dialog (ignored on iOS).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **`popup`**                  | <code>boolean</code> | When `true`, Android shows the OS speech dialog instead of running inline recognition. Defaults to `false`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| **`partialResults`**         | <code>boolean</code> | Emits partial transcription updates through the `partialResults` listener while audio is captured.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **`addPunctuation`**         | <code>boolean</code> | Enables native punctuation handling where supported (iOS 16+).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| **`useOnDeviceRecognition`** | <code>boolean</code> | Opt in to the platform's newer on-device recognition path when available. On iOS 26+, this uses Apple's `SpeechAnalyzer` / `SpeechTranscriber` pipeline. On recent Android versions, this uses the on-device `SpeechRecognizer` path. It is intentionally opt-in so existing apps keep the legacy flow unless they choose to roll out the new behavior. Use {@link SpeechRecognitionPlugin.isOnDeviceRecognitionAvailable} before enabling it in production. Platform SDK docs: iOS: [Speech](https://developer.apple.com/documentation/speech), [SpeechAnalyzer](https://developer.apple.com/documentation/speech/speechanalyzer), [SpeechTranscriber](https://developer.apple.com/documentation/speech/speechtranscriber) Android: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) Defaults to `false`. |
| **`allowForSilence`**        | <code>number</code>  | Allow a number of milliseconds of silence before splitting the recognition session into segments. Required to be greater than zero and currently supported on Android only.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |


#### SpeechRecognitionMatches

| Prop          | Type                  |
| ------------- | --------------------- |
| **`matches`** | <code>string[]</code> |


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
| **`status`** | <code>'started' \| 'stopped'</code> |


### Type Aliases


#### Pick

From T, pick a set of properties whose keys are in the union K

<code>{ [P in K]: T[P]; }</code>


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>

</docgen-api>
