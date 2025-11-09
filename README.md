# @capgo/capacitor-speech-recognition
 <a href="https://capgo.app/"><img src='https://raw.githubusercontent.com/Cap-go/capgo/main/assets/capgo_banner.png' alt='Capgo - Instant updates for capacitor'/></a>

<div align="center">
  <h2><a href="https://capgo.app/?ref=plugin_speech_recognition"> ➡️ Get Instant updates for your App with Capgo</a></h2>
  <h2><a href="https://capgo.app/consulting/?ref=plugin_speech_recognition"> Missing a feature? We’ll build the plugin for you 💪</a></h2>
</div>

Natural, low-latency speech recognition for Capacitor apps with parity across iOS and Android, streaming partial results, and permission helpers baked in.

## Documentation

The most complete doc is available here: https://capgo.app/docs/plugins/speech-recognition/

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
* [`getSupportedLanguages()`](#getsupportedlanguages)
* [`isListening()`](#islistening)
* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions()`](#requestpermissions)
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

| Prop                  | Type                 | Description                                                                                                                                                                 |
| --------------------- | -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`language`**        | <code>string</code>  | Locale identifier such as `en-US`. When omitted the device language is used.                                                                                                |
| **`maxResults`**      | <code>number</code>  | Maximum number of final matches returned by native APIs. Defaults to `5`.                                                                                                   |
| **`prompt`**          | <code>string</code>  | Prompt message shown inside the Android system dialog (ignored on iOS).                                                                                                     |
| **`popup`**           | <code>boolean</code> | When `true`, Android shows the OS speech dialog instead of running inline recognition. Defaults to `false`.                                                                 |
| **`partialResults`**  | <code>boolean</code> | Emits partial transcription updates through the `partialResults` listener while audio is captured.                                                                          |
| **`addPunctuation`**  | <code>boolean</code> | Enables native punctuation handling where supported (iOS 16+).                                                                                                              |
| **`allowForSilence`** | <code>number</code>  | Allow a number of milliseconds of silence before splitting the recognition session into segments. Required to be greater than zero and currently supported on Android only. |


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


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>

</docgen-api>
