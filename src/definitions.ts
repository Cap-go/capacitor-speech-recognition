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
 * Finite state values for the speech recognition session lifecycle.
 */
export type ListeningFiniteState =
  | 'startingListening'
  | 'started'
  | 'stoppingListening'
  | 'stopped';

/**
 * Reason codes explaining why a state transition occurred.
 */
export type ListeningReason =
  | 'userStart'
  | 'userStop'
  | 'results'
  | 'silence'
  | 'error'
  | 'unknown';

/**
 * Raised when the listening state changes.
 * 
 * **Extended in v7.1+** with state machine tracking.
 * The `status` field is kept for backward compatibility.
 */
export interface SpeechRecognitionListeningEvent {
  /**
   * Finite state of the recognition session.
   * @since 7.1.0
   */
  state?: ListeningFiniteState;
  
  /**
   * Unique identifier for this listening session.
   * Increments on each `start()` call.
   * @since 7.1.0
   */
  sessionId?: number;
  
  /**
   * Why this state transition occurred.
   * @since 7.1.0
   */
  reason?: ListeningReason;
  
  /**
   * Error code if the transition was due to an error.
   * @since 7.1.0
   */
  errorCode?: string;
  
  /**
   * Simplified binary status for backward compatibility.
   * - Maps to `'started'` when `state === 'started'`
   * - Maps to `'stopped'` when `state === 'stopped'`
   */
  status: 'started' | 'stopped';
}

/**
 * Raised when a recognition error occurs.
 * 
 * Previously errors were only reported through promise rejections.
 * Now all errors emit this event.
 * 
 * @since 7.1.0
 */
export interface SpeechRecognitionErrorEvent {
  /**
   * Machine-readable error code such as:
   * - `NO_MATCH` - No speech detected (silence timeout)
   * - `SPEECH_TIMEOUT` - Speech input timeout
   * - `NETWORK` - Network error
   * - `AUDIO` - Audio recording error
   * - `CLIENT` - Client-side error
   * - etc.
   */
  code: string;
  
  /**
   * Human-readable error message.
   */
  message: string;
  
  /**
   * Session ID that encountered this error.
   */
  sessionId: number;
}

/**
 * Emitted after the plugin fully tears down and recreates the native recognizer.
 * 
 * This is a low-level signal indicating it's safe to call `start()` again
 * after an error or session completion.
 * 
 * @since 7.1.0
 */
export interface SpeechRecognitionReadyEvent {
  /**
   * The session that was just finished.
   */
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
   * 
   * All errors now emit this event in addition to rejecting promises.
   * Particularly useful for detecting silence timeouts and other non-critical errors.
   * 
   * @since 7.1.0
   */
  addListener(
    eventName: 'error',
    listenerFunc: (event: SpeechRecognitionErrorEvent) => void,
  ): Promise<PluginListenerHandle>;
  /**
   * Listen for the recognizer becoming ready after a session ends.
   * 
   * This is a low-level event indicating when the native SpeechRecognizer
   * has been fully torn down and recreated. Useful for debugging or
   * implementing retry logic.
   * 
   * @since 7.1.0
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
