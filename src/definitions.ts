import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

/**
 * Permission map returned by `checkPermissions` and `requestPermissions`.
 *
 * On Android the state maps to the `RECORD_AUDIO` permission.
 * On iOS it combines speech recognition plus microphone permission.
 */
export interface SpeechRecognitionPermissionStatus {
  speechRecognition: PermissionState;
}

/**
 * Configure how the recognizer behaves when calling {@link SpeechRecognitionPlugin.start}.
 */
export interface SpeechRecognitionStartOptions {
  /**
   * Locale identifier such as `en-US`. When omitted the device language is used.
   */
  language?: string;
  /**
   * Maximum number of final matches returned by native APIs. Defaults to `5`.
   */
  maxResults?: number;
  /**
   * Prompt message shown inside the Android system dialog (ignored on iOS).
   */
  prompt?: string;
  /**
   * When `true`, Android shows the OS speech dialog instead of running inline recognition.
   * Defaults to `false`.
   */
  popup?: boolean;
  /**
   * Emits partial transcription updates through the `partialResults` listener while audio is captured.
   */
  partialResults?: boolean;
  /**
   * Enables native punctuation handling where supported (iOS 16+).
   */
  addPunctuation?: boolean;
  /**
   * Opt in to the platform's newer on-device recognition path when available.
   *
   * On iOS 26+, this uses Apple's `SpeechAnalyzer` / `SpeechTranscriber` pipeline.
   * On recent Android versions, this uses the on-device `SpeechRecognizer` path.
   *
   * It is intentionally opt-in so existing apps keep the legacy flow unless they choose
   * to roll out the new behavior.
   *
   * Use {@link SpeechRecognitionPlugin.isOnDeviceRecognitionAvailable} before enabling it in production.
   *
   * Platform SDK docs:
   * iOS: [Speech](https://developer.apple.com/documentation/speech),
   * [SpeechAnalyzer](https://developer.apple.com/documentation/speech/speechanalyzer),
   * [SpeechTranscriber](https://developer.apple.com/documentation/speech/speechtranscriber)
   * Android: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
   *
   * Defaults to `false`.
   */
  useOnDeviceRecognition?: boolean;
  /**
   * Allow a number of milliseconds of silence before splitting the recognition session into segments.
   * Required to be greater than zero and currently supported on Android only.
   */
  allowForSilence?: number;
  /**
   * EXPERIMENTAL: Keep a PTT session alive across silence by restarting recognition while the button stays held.
   *
   * This restart behavior is implemented for Android inline recognition and iOS native recognition.
   */
  continuousPTT?: boolean;
}

/**
 * Raised whenever a partial transcription is produced.
 */
export interface SpeechRecognitionPartialResultEvent {
  matches: string[];
  /**
   * Accumulated transcription from earlier continuous PTT cycles.
   */
  accumulated?: string;
  /**
   * Final accumulated text including the current result.
   */
  accumulatedText?: string;
  /**
   * `true` when the plugin is restarting recognition inside a continuous PTT session.
   */
  isRestarting?: boolean;
  /**
   * `true` when the payload was emitted by `forceStop()`.
   */
  forced?: boolean;
}

/**
 * Raised whenever a segmented result is produced (Android only).
 */
export interface SpeechRecognitionSegmentResultEvent {
  matches: string[];
}

/**
 * Finite state values for the recognition session lifecycle.
 */
export type ListeningFiniteState = 'startingListening' | 'started' | 'stoppingListening' | 'stopped';

/**
 * Why a listening state transition happened.
 */
export type ListeningReason = 'userStart' | 'userStop' | 'forceStop' | 'results' | 'silence' | 'error' | 'unknown';

/**
 * Raised when the listening state changes.
 *
 * The original `status` field is preserved for backward compatibility and is present
 * on the binary `started` / `stopped` states.
 */
export interface SpeechRecognitionListeningEvent {
  /**
   * Finite state of the recognition session.
   */
  state?: ListeningFiniteState;
  /**
   * Unique identifier for the current listening session.
   */
  sessionId?: number;
  /**
   * Why this state transition occurred.
   */
  reason?: ListeningReason;
  /**
   * Error code when the transition is caused by an error.
   */
  errorCode?: string;
  /**
   * Backward-compatible binary state used by earlier releases.
   */
  status?: 'started' | 'stopped';
}

/**
 * Raised whenever native recognition reports an error.
 */
export interface SpeechRecognitionErrorEvent {
  code: string;
  message: string;
  sessionId: number;
}

/**
 * Emitted after native resources have been torn down and the plugin is ready for another session.
 */
export interface SpeechRecognitionReadyEvent {
  sessionId: number;
}

export interface SpeechRecognitionAvailability {
  available: boolean;
}

export interface SpeechRecognitionMatches {
  matches?: string[];
}

export interface SpeechRecognitionLanguages {
  languages: string[];
}

export interface SpeechRecognitionListening {
  listening: boolean;
}

/**
 * Options for {@link SpeechRecognitionPlugin.forceStop}.
 */
export interface ForceStopOptions {
  /**
   * Android only: timeout in milliseconds before forcing stop via destroy/recreate.
   *
   * On iOS, the current session is stopped immediately and this value is ignored.
   *
   * Defaults to `1500`.
   */
  timeout?: number;
}

/**
 * Result from {@link SpeechRecognitionPlugin.getLastPartialResult}.
 */
export interface LastPartialResult {
  /**
   * Whether a partial result is currently cached.
   */
  available: boolean;
  /**
   * The most recent transcript text known to the native recognizer.
   */
  text: string;
  /**
   * All current match alternatives when available.
   */
  matches?: string[];
}

/**
 * Options for {@link SpeechRecognitionPlugin.setPTTState}.
 */
export interface PTTStateOptions {
  /**
   * Whether the PTT button is currently held.
   */
  held: boolean;
}

export interface SpeechRecognitionPlugin {
  /**
   * Checks whether the native speech recognition service is usable on the current device.
   */
  available(): Promise<SpeechRecognitionAvailability>;
  /**
   * Checks whether the platform's newer on-device recognition path is available for the selected locale.
   *
   * This is the capability check you should use before enabling `useOnDeviceRecognition`.
   * A `true` result means the current device, OS version, and locale can use the newer
   * on-device path for that platform.
   *
   * Returns `false` when the device only supports the legacy recognizer path.
   *
   * Platform SDK docs:
   * iOS: [Speech](https://developer.apple.com/documentation/speech)
   * Android: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
   */
  isOnDeviceRecognitionAvailable(
    options?: Pick<SpeechRecognitionStartOptions, 'language'>,
  ): Promise<SpeechRecognitionAvailability>;
  /**
   * Begins capturing audio and transcribing speech.
   *
   * When `partialResults` is `true`, the returned promise resolves immediately and updates are
   * streamed through the `partialResults` listener until the session ends.
   *
   * The default path keeps the legacy recognizer behavior for backward compatibility.
   * Pass `useOnDeviceRecognition: true` only after checking
   * {@link SpeechRecognitionPlugin.isOnDeviceRecognitionAvailable}.
   */
  start(options?: SpeechRecognitionStartOptions): Promise<SpeechRecognitionMatches>;
  /**
   * Stops listening and tears down native resources.
   */
  stop(): Promise<void>;
  /**
   * Force stops the current session.
   *
   * On Android, this first tries a normal stop and then falls back to destroy/recreate after `timeout`.
   * On iOS, the current session is stopped immediately.
   *
   * If a partial transcript is cached, it is emitted through the `partialResults` listener with `forced: true`.
   */
  forceStop(options?: ForceStopOptions): Promise<void>;
  /**
   * Gets the last cached partial transcription result.
   */
  getLastPartialResult(): Promise<LastPartialResult>;
  /**
   * Updates the current push-to-talk button state.
   *
   * Use this together with `continuousPTT` or with a custom hold-to-talk flow.
   */
  setPTTState(options: PTTStateOptions): Promise<void>;
  /**
   * Gets the locales supported by the underlying recognizer.
   *
   * Android 13+ devices no longer expose this list; in that case `languages` is empty.
   */
  getSupportedLanguages(): Promise<SpeechRecognitionLanguages>;
  /**
   * Returns whether the plugin is actively listening for speech.
   */
  isListening(): Promise<SpeechRecognitionListening>;
  /**
   * Gets the current permission state.
   */
  checkPermissions(): Promise<SpeechRecognitionPermissionStatus>;
  /**
   * Requests the microphone + speech recognition permissions.
   */
  requestPermissions(): Promise<SpeechRecognitionPermissionStatus>;
  /**
   * Returns the native plugin version bundled with this package.
   *
   * Useful when reporting issues to confirm that native and JS versions match.
   */
  getPluginVersion(): Promise<{ version: string }>;
  /**
   * Listen for segmented session completion events (Android only).
   */
  addListener(eventName: 'endOfSegmentedSession', listenerFunc: () => void): Promise<PluginListenerHandle>;
  /**
   * Listen for segmented recognition results (Android only).
   */
  addListener(
    eventName: 'segmentResults',
    listenerFunc: (event: SpeechRecognitionSegmentResultEvent) => void,
  ): Promise<PluginListenerHandle>;
  /**
   * Listen for partial transcription updates emitted while `partialResults` is enabled.
   */
  addListener(
    eventName: 'partialResults',
    listenerFunc: (event: SpeechRecognitionPartialResultEvent) => void,
  ): Promise<PluginListenerHandle>;
  /**
   * Listen for changes to the native listening state.
   */
  addListener(
    eventName: 'listeningState',
    listenerFunc: (event: SpeechRecognitionListeningEvent) => void,
  ): Promise<PluginListenerHandle>;
  /**
   * Listen for recognition errors.
   */
  addListener(
    eventName: 'error',
    listenerFunc: (event: SpeechRecognitionErrorEvent) => void,
  ): Promise<PluginListenerHandle>;
  /**
   * Listen for the recognizer becoming ready for another session.
   */
  addListener(
    eventName: 'readyForNextSession',
    listenerFunc: (event: SpeechRecognitionReadyEvent) => void,
  ): Promise<PluginListenerHandle>;
  /**
   * Removes every registered listener.
   */
  removeAllListeners(): Promise<void>;
}
