import { WebPlugin } from '@capacitor/core';

import type {
  ForceStopOptions,
  LastPartialResult,
  PTTStateOptions,
  SpeechRecognitionAvailability,
  SpeechRecognitionLanguages,
  SpeechRecognitionListening,
  SpeechRecognitionMatches,
  SpeechRecognitionPermissionStatus,
  SpeechRecognitionPlugin,
  SpeechRecognitionStartOptions,
} from './definitions';

export class SpeechRecognitionWeb extends WebPlugin implements SpeechRecognitionPlugin {
  available(): Promise<SpeechRecognitionAvailability> {
    throw this.unimplemented('Speech recognition is not available on the web.');
  }

  start(_options?: SpeechRecognitionStartOptions): Promise<SpeechRecognitionMatches> {
    throw this.unimplemented('Speech recognition is not available on the web.');
  }

  stop(): Promise<void> {
    throw this.unimplemented('Speech recognition is not available on the web.');
  }

  forceStop(_options?: ForceStopOptions): Promise<void> {
    throw this.unimplemented('Speech recognition is not available on the web.');
  }

  getLastPartialResult(): Promise<LastPartialResult> {
    throw this.unimplemented('Speech recognition is not available on the web.');
  }

  setPTTState(_options: PTTStateOptions): Promise<void> {
    throw this.unimplemented('Speech recognition is not available on the web.');
  }

  getSupportedLanguages(): Promise<SpeechRecognitionLanguages> {
    throw this.unimplemented('Speech recognition is not available on the web.');
  }

  isListening(): Promise<SpeechRecognitionListening> {
    throw this.unimplemented('Speech recognition is not available on the web.');
  }

  checkPermissions(): Promise<SpeechRecognitionPermissionStatus> {
    throw this.unimplemented('Speech recognition permissions are not handled on the web.');
  }

  requestPermissions(): Promise<SpeechRecognitionPermissionStatus> {
    throw this.unimplemented('Speech recognition permissions are not handled on the web.');
  }

  async getPluginVersion(): Promise<{ version: string }> {
    return { version: 'web' };
  }
}
