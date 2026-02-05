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
   * Allow a number of milliseconds of silence before splitting the recognition session into segments.
   * Required to be greater than zero and currently supported on Android only.
   */
  allowForSilence?: number;
  /**
   * EXPERIMENTAL: Enable continuous PTT mode.
   * When enabled and used with setPTTState(), recognition will auto-restart on silence
   * while the PTT button is held, accumulating results across restarts.
   * Android only.
   */
  continuousPTT?: boolean;
}

/**
 * Raised whenever a partial transcription is produced.
 */
export interface SpeechRecognitionPartialResultEvent {
  matches: string[];
  /**
   * Accumulated transcription across continuous PTT restarts (Android only).
   */
  accumulated?: string;
  /**
   * Final accumulated text including the current result (Android only).
   */
  accumulatedText?: string;
  /**
   * True when recognition is restarting in continuous PTT mode (Android only).
   */
  isRestarting?: boolean;
  /**
   * True when result was emitted due to force stop timeout (Android only).
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

/**
 * Options for the forceStop method.
 */
export interface ForceStopOptions {
  /**
   * Timeout in milliseconds before forcing stop via destroy/recreate.
   * Defaults to 1500ms.
   */
  timeout?: number;
}

/**
 * Result from getLastPartialResult.
 */
export interface LastPartialResult {
  /**
   * Whether a partial result is available.
   */
  available: boolean;
  /**
   * The last partial transcription text.
   */
  text: string;
  /**
   * All partial match alternatives.
   */
  matches?: string[];
}

/**
 * Options for setPTTState method.
 */
export interface PTTStateOptions {
  /**
   * Whether the PTT button is currently held.
   * Set to true on button press, false on button release.
   */
  held: boolean;
}

export interface SpeechRecognitionPlugin {
  /**
   * Checks whether the native speech recognition service is usable on the current device.
   */
  available(): Promise<SpeechRecognitionAvailability>;
  /**
   * Begins capturing audio and transcribing speech.
   *
   * When `partialResults` is `true`, the returned promise resolves immediately and updates are
   * streamed through the `partialResults` listener until {@link stop} is called.
   */
  start(options?: SpeechRecognitionStartOptions): Promise<SpeechRecognitionMatches>;
  /**
   * Stops listening and tears down native resources.
   */
  stop(): Promise<void>;
  /**
   * Force stops recognition with a timeout fallback.
   *
   * This is useful for Push-to-Talk (PTT) implementations where you need
   * reliable stopping behavior. The Android SpeechRecognizer.stopListening()
   * doesn't always stop audio capture reliably.
   *
   * This method:
   * 1. Tries graceful stopListening() first
   * 2. After timeout, forces stop by destroying and recreating the recognizer
   * 3. Emits the last cached partial result via the `partialResults` event
   *
   * To retrieve the last transcription after force stop, use {@link getLastPartialResult}.
   *
   * @platform Android
   * @param options - Optional timeout configuration
   */
  forceStop(options?: ForceStopOptions): Promise<void>;
  /**
   * Gets the last cached partial transcription result.
   *
   * Useful for retrieving what was heard before a force stop,
   * or checking the current partial state at any time.
   *
   * @platform Android
   */
  getLastPartialResult(): Promise<LastPartialResult>;
  /**
   * EXPERIMENTAL: Set PTT button state for continuous PTT mode.
   *
   * When continuousPTT is enabled in start() and held is true,
   * recognition will auto-restart on silence, accumulating results.
   * Call with held=false when the button is released.
   *
   * @platform Android
   * @param options - PTT state options
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
   * Removes every registered listener.
   */
  removeAllListeners(): Promise<void>;
}
