# @capgo/capacitor-speech-recognition
<a href="https://capgo.app/"><img src="https://capgo.app/readme-banner.svg?repo=Cap-go/capacitor-speech-recognition" alt="Capgo - Instant updates for Capacitor" /></a>

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

You can use our AI-Assisted Setup to install the plugin. Add the Capgo skills to your AI tool using the following command:

```bash
npx skills add https://github.com/cap-go/capacitor-skills --skill capacitor-plugins
```

Then use the following prompt:

```text
Use the `capacitor-plugins` skill from `cap-go/capacitor-skills` to install the `@capgo/capacitor-speech-recognition` plugin in my project.
```

If you prefer Manual Setup, install the plugin by running the following commands and follow the platform-specific instructions below:

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

### iOS fallback and contextual strings

iOS uses `SFSpeechRecognizer` by default, including on versions below iOS 26.
Set `useOnDeviceRecognition: true` only when you want the iOS 26+
`SpeechAnalyzer` path and `isOnDeviceRecognitionAvailable()` reports support.
If that newer path is unavailable, the plugin falls back to `SFSpeechRecognizer`.

Use `contextualStrings` to bias recognition toward app-specific terms on the
`SFSpeechRecognizer` path:

```ts
await SpeechRecognition.start({
  language: 'en-US',
  partialResults: true,
  contextualStrings: ['Capgo', 'Live Update', 'channel override'],
});
```

## On-device recognition mode

This plugin now supports an opt-in on-device recognition path behind the explicit
`useOnDeviceRecognition` flag.

### What it is

The default path keeps the long-standing recognizer flow for backward compatibility.
`useOnDeviceRecognition` switches to a newer local speech pipeline when the platform supports it:

- On iOS 26+, it uses Apple's `SpeechAnalyzer` / `SpeechTranscriber` stack.
- On older iOS versions, unsupported iOS 26+ locales, or when the flag is disabled, it uses `SFSpeechRecognizer`.
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

## Push-to-talk and session events

This plugin also supports a push-to-talk oriented flow built around three APIs:

- `setPTTState({ held })` lets your UI tell the plugin when the button is pressed or released.
- `forceStop()` stops the active session immediately and emits the last cached partial result with `forced: true` when available.
- `getLastPartialResult()` lets you read back the latest cached transcript at any point.

`continuousPTT` is the experimental cross-platform mode that keeps a held push-to-talk session alive by restarting recognition as speech segments finalize. Android and iOS both support this restart flow for inline/native recognition.

The plugin also emits deterministic session lifecycle events so UIs can react cleanly:

- `listeningState` now carries `state`, `sessionId`, `reason`, and optional `errorCode` in addition to the legacy `status`.
- `error` is emitted for every native recognizer error instead of relying only on promise rejections.
- `readyForNextSession` signals when native resources are torn down and the plugin is ready for another start.

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
* [`addListener('error', ...)`](#addlistenererror-)
* [`addListener('readyForNextSession', ...)`](#addlistenerreadyfornextsession-)
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
streamed through the `partialResults` listener until the session ends.

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


### forceStop(...)

```typescript
forceStop(options?: ForceStopOptions | undefined) => Promise<void>
```

Force stops the current session.

On Android, this first tries a normal stop and then falls back to destroy/recreate after `timeout`.
On iOS, the current session is stopped immediately.

If a partial transcript is cached, it is emitted through the `partialResults` listener with `forced: true`.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#forcestopoptions">ForceStopOptions</a></code> |

--------------------


### getLastPartialResult()

```typescript
getLastPartialResult() => Promise<LastPartialResult>
```

Gets the last cached partial transcription result.

**Returns:** <code>Promise&lt;<a href="#lastpartialresult">LastPartialResult</a>&gt;</code>

--------------------


### setPTTState(...)

```typescript
setPTTState(options: PTTStateOptions) => Promise<void>
```

Updates the current push-to-talk button state.

Use this together with `continuousPTT` or with a custom hold-to-talk flow.

| Param         | Type                                                        |
| ------------- | ----------------------------------------------------------- |
| **`options`** | <code><a href="#pttstateoptions">PTTStateOptions</a></code> |

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


### addListener('error', ...)

```typescript
addListener(eventName: 'error', listenerFunc: (event: SpeechRecognitionErrorEvent) => void) => Promise<PluginListenerHandle>
```

Listen for recognition errors.

| Param              | Type                                                                                                    |
| ------------------ | ------------------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'error'</code>                                                                                    |
| **`listenerFunc`** | <code>(event: <a href="#speechrecognitionerrorevent">SpeechRecognitionErrorEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('readyForNextSession', ...)

```typescript
addListener(eventName: 'readyForNextSession', listenerFunc: (event: SpeechRecognitionReadyEvent) => void) => Promise<PluginListenerHandle>
```

Listen for the recognizer becoming ready for another session.

| Param              | Type                                                                                                    |
| ------------------ | ------------------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'readyForNextSession'</code>                                                                      |
| **`listenerFunc`** | <code>(event: <a href="#speechrecognitionreadyevent">SpeechRecognitionReadyEvent</a>) =&gt; void</code> |

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

| Prop                         | Type                  | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| ---------------------------- | --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`language`**               | <code>string</code>   | Locale identifier such as `en-US`. When omitted the device language is used.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **`maxResults`**             | <code>number</code>   | Maximum number of final matches returned by native APIs. Defaults to `5`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| **`prompt`**                 | <code>string</code>   | Prompt message shown inside the Android system dialog (ignored on iOS).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **`popup`**                  | <code>boolean</code>  | When `true`, Android shows the OS speech dialog instead of running inline recognition. Defaults to `false`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| **`partialResults`**         | <code>boolean</code>  | Emits partial transcription updates through the `partialResults` listener while audio is captured.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **`addPunctuation`**         | <code>boolean</code>  | Enables native punctuation handling where supported (iOS 16+).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| **`contextualStrings`**      | <code>string[]</code> | Words or phrases that should be recognized more accurately by native speech APIs. On iOS, these are passed to `SFSpeechRecognitionRequest.contextualStrings` when the plugin uses the legacy `SFSpeechRecognizer` path. That path is the default on all iOS versions, the fallback below iOS 26, and still available on iOS 26+ by leaving `useOnDeviceRecognition` disabled. Ignored by Android and by the iOS 26+ `SpeechAnalyzer` path.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| **`useOnDeviceRecognition`** | <code>boolean</code>  | Opt in to the platform's newer on-device recognition path when available. On iOS 26+, this uses Apple's `SpeechAnalyzer` / `SpeechTranscriber` pipeline. On recent Android versions, this uses the on-device `SpeechRecognizer` path. It is intentionally opt-in so existing apps keep the legacy flow unless they choose to roll out the new behavior. On iOS, leaving this disabled keeps `SFSpeechRecognizer` on every supported OS version. Enabling it on older iOS versions or unsupported locales falls back to `SFSpeechRecognizer`. Use {@link SpeechRecognitionPlugin.isOnDeviceRecognitionAvailable} before enabling it in production. Platform SDK docs: iOS: [Speech](https://developer.apple.com/documentation/speech), [SpeechAnalyzer](https://developer.apple.com/documentation/speech/speechanalyzer), [SpeechTranscriber](https://developer.apple.com/documentation/speech/speechtranscriber) Android: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) Defaults to `false`. |
| **`allowForSilence`**        | <code>number</code>   | Allow a number of milliseconds of silence before splitting the recognition session into segments. Required to be greater than zero and currently supported on Android only.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| **`continuousPTT`**          | <code>boolean</code>  | EXPERIMENTAL: Keep a PTT session alive across silence by restarting recognition while the button stays held. This restart behavior is implemented for Android inline recognition and iOS native recognition.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **`muteRecognizerBeep`**     | <code>boolean</code>  | Suppresses the Android system beep when inline recognition starts or restarts. Uses a best-effort combination of an undocumented recognizer intent extra and temporary notification/system stream volume muting. Some devices ignore the intent extra; the volume fallback is the portable path. Defaults to `true` when `continuousPTT` is enabled.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |


#### SpeechRecognitionMatches

| Prop          | Type                  |
| ------------- | --------------------- |
| **`matches`** | <code>string[]</code> |


#### ForceStopOptions

Options for {@link SpeechRecognitionPlugin.forceStop}.

| Prop          | Type                | Description                                                                                                                                                                       |
| ------------- | ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`timeout`** | <code>number</code> | Android only: timeout in milliseconds before forcing stop via destroy/recreate. On iOS, the current session is stopped immediately and this value is ignored. Defaults to `1500`. |


#### LastPartialResult

Result from {@link SpeechRecognitionPlugin.getLastPartialResult}.

| Prop            | Type                  | Description                                                     |
| --------------- | --------------------- | --------------------------------------------------------------- |
| **`available`** | <code>boolean</code>  | Whether a partial result is currently cached.                   |
| **`text`**      | <code>string</code>   | The most recent transcript text known to the native recognizer. |
| **`matches`**   | <code>string[]</code> | All current match alternatives when available.                  |


#### PTTStateOptions

Options for {@link SpeechRecognitionPlugin.setPTTState}.

| Prop       | Type                 | Description                                                                                                                                                                                                                                                         |
| ---------- | -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`held`** | <code>boolean</code> | Whether the PTT button is currently held.                                                                                                                                                                                                                           |
| **`mute`** | <code>boolean</code> | When set, updates whether Android should suppress the recognizer start beep for the active session. Beep suppression is best-effort and device-specific; see {@link <a href="#speechrecognitionstartoptions">SpeechRecognitionStartOptions.muteRecognizerBeep</a>}. |


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

| Prop                  | Type                  | Description                                                                                                                       |
| --------------------- | --------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| **`matches`**         | <code>string[]</code> | Current recognition matches when the native recognizer reports them. This can be omitted for forced or accumulated-only payloads. |
| **`accumulated`**     | <code>string</code>   | Accumulated transcription from earlier continuous PTT cycles.                                                                     |
| **`accumulatedText`** | <code>string</code>   | Final accumulated text including the current result.                                                                              |
| **`isRestarting`**    | <code>boolean</code>  | `true` when the plugin is restarting recognition inside a continuous PTT session.                                                 |
| **`forced`**          | <code>boolean</code>  | `true` when the payload was emitted by `forceStop()`.                                                                             |


#### SpeechRecognitionListeningEvent

Raised when the listening state changes.

The original `status` field is preserved for backward compatibility and is present
on the binary `started` / `stopped` states.

| Prop            | Type                                                                  | Description                                                |
| --------------- | --------------------------------------------------------------------- | ---------------------------------------------------------- |
| **`state`**     | <code><a href="#listeningfinitestate">ListeningFiniteState</a></code> | Finite state of the recognition session.                   |
| **`sessionId`** | <code>number</code>                                                   | Unique identifier for the current listening session.       |
| **`reason`**    | <code><a href="#listeningreason">ListeningReason</a></code>           | Why this state transition occurred.                        |
| **`errorCode`** | <code>string</code>                                                   | Error code when the transition is caused by an error.      |
| **`status`**    | <code>'started' \| 'stopped'</code>                                   | Backward-compatible binary state used by earlier releases. |


#### SpeechRecognitionErrorEvent

Raised whenever native recognition reports an error.

| Prop            | Type                |
| --------------- | ------------------- |
| **`code`**      | <code>string</code> |
| **`message`**   | <code>string</code> |
| **`sessionId`** | <code>number</code> |


#### SpeechRecognitionReadyEvent

Emitted after native resources have been torn down and the plugin is ready for another session.

| Prop            | Type                |
| --------------- | ------------------- |
| **`sessionId`** | <code>number</code> |


### Type Aliases


#### Pick

From T, pick a set of properties whose keys are in the union K

<code>{ [P in K]: T[P]; }</code>


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>


#### ListeningFiniteState

Finite state values for the recognition session lifecycle.

<code>'startingListening' | 'started' | 'stoppingListening' | 'stopped'</code>


#### ListeningReason

Why a listening state transition happened.

<code>'userStart' | 'userStop' | 'forceStop' | 'results' | 'silence' | 'error' | 'unknown'</code>

</docgen-api>
