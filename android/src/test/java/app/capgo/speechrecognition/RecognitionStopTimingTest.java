package app.capgo.speechrecognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecognitionStopTimingTest {

    @Test
    public void stopFallbackTimeout_allowsSlowOnDeviceRecognizers() {
        assertEquals(2000, RecognitionStopTiming.STOP_FALLBACK_TIMEOUT_MS);
        assertTrue(RecognitionStopTiming.STOP_FALLBACK_TIMEOUT_MS > 500);
    }
}
