package app.capgo.speechrecognition;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;

/**
 * Samples microphone amplitude alongside SpeechRecognizer.
 *
 * SpeechRecognizer does not expose metering, so this uses a lightweight
 * {@link AudioRecord} on {@link MediaRecorder.AudioSource#VOICE_RECOGNITION}.
 * If the recorder cannot start (OEM mic exclusivity), metering is skipped
 * without failing recognition.
 */
final class AudioLevelMeter {

    private static final String TAG = "SpeechRecognition";
    private static final int SAMPLE_RATE = 16000;
    private static final long EMIT_INTERVAL_MS = 66; // ~15 Hz

    interface Emitter {
        void emit(double level);
    }

    private final Plugin plugin;
    private final Emitter emitter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean running;

    AudioLevelMeter(Plugin plugin, Emitter emitter) {
        this.plugin = plugin;
        this.emitter = emitter;
    }

    void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            worker = new Thread(this::runLoop, "capgo-speech-audio-level");
            worker.setDaemon(true);
            worker.start();
        }
    }

    void stop() {
        Thread toJoin;
        synchronized (lock) {
            running = false;
            toJoin = worker;
            worker = null;
        }
        if (toJoin != null) {
            toJoin.interrupt();
            try {
                toJoin.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Logger.warn(TAG, "AudioLevelMeter: invalid AudioRecord buffer size; metering disabled");
            return;
        }

        int bufferSize = Math.max(minBuf, SAMPLE_RATE / 10);
        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            );
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Logger.warn(TAG, "AudioLevelMeter: AudioRecord failed to initialize; metering disabled");
                return;
            }
            recorder.startRecording();
        } catch (SecurityException | IllegalStateException | IllegalArgumentException ex) {
            Logger.warn(TAG, "AudioLevelMeter: unable to start AudioRecord; metering disabled: " + ex.getMessage());
            releaseQuietly(recorder);
            return;
        }

        short[] samples = new short[bufferSize / 2];
        long lastEmit = 0;
        try {
            while (running) {
                int read = recorder.read(samples, 0, samples.length);
                if (read <= 0) {
                    continue;
                }
                long now = System.currentTimeMillis();
                if (now - lastEmit < EMIT_INTERVAL_MS) {
                    continue;
                }
                lastEmit = now;
                double level = normalizePeak(samples, read);
                mainHandler.post(() -> {
                    if (running) {
                        emitter.emit(level);
                    }
                });
            }
        } finally {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            }
            releaseQuietly(recorder);
        }
    }

    private static double normalizePeak(short[] samples, int read) {
        int peak = 0;
        for (int i = 0; i < read; i++) {
            int v = Math.abs(samples[i]);
            if (v > peak) {
                peak = v;
            }
        }
        // 16-bit PCM peak → 0..1
        return Math.max(0.0, Math.min(1.0, peak / 32767.0));
    }

    private static void releaseQuietly(AudioRecord recorder) {
        if (recorder == null) {
            return;
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
            // no-op
        }
    }
}
