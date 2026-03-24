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
}

/**
 * Raised whenever a partial transcription is produced.
 */
export interface SpeechRecognitionPartialResultEvent {
  matches: string[];
}

/**
 * Raised whenever a segmented result is produced (Android only).
 */
export interface SpeechRecognitionSegmentResultEvent {
  matches: string[];
}

/**
 * Raised when the listening state changes.
 */
export interface SpeechRecognitionListeningEvent {
  status: 'started' | 'stopped';
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
   * streamed through the `partialResults` listener until {@link stop} is called.
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
   * Removes every registered listener.
   */
  removeAllListeners(): Promise<void>;
}
