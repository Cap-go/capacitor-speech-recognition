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
   * 3. Returns the last cached partial result so no speech is lost
   *
   * @param options - Optional timeout configuration
   */
  forceStop(options?: ForceStopOptions): Promise<void>;
  /**
   * Gets the last cached partial transcription result.
   *
   * Useful for retrieving what was heard before a force stop,
   * or checking the current partial state at any time.
   */
  getLastPartialResult(): Promise<LastPartialResult>;
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
