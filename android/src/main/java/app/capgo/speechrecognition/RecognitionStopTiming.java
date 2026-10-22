package app.capgo.speechrecognition;

/** Timeouts used when inline recognition is stopped before final results arrive. */
public final class RecognitionStopTiming {

    private RecognitionStopTiming() {}

    /**
     * Fallback delay after {@link android.speech.SpeechRecognizer#stopListening()} when
     * {@code onResults} has not fired yet. Slow on-device recognizers can need more than 500 ms.
     */
    public static final int STOP_FALLBACK_TIMEOUT_MS = 2000;
}
