package app.capgo.speechrecognition;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONArray;

@CapacitorPlugin(
    name = "SpeechRecognition",
    permissions = { @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = SpeechRecognitionPlugin.SPEECH_RECOGNITION) }
)
public class SpeechRecognitionPlugin extends Plugin implements Constants {

    public static final String SPEECH_RECOGNITION = "speechRecognition";
    private static final String TAG = "SpeechRecognition";
    private static final String PLUGIN_VERSION = "7.0.0";

    private Receiver languageReceiver;
    private SpeechRecognizer speechRecognizer;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean listening = false;
    private JSONArray previousPartialResults = new JSONArray();

    // PTT (Push-to-Talk) support fields
    private final Handler pttHandler = new Handler(Looper.getMainLooper());
    private Runnable forceStopRunnable;
    private boolean forceStopped = false;

    // Continuous PTT mode fields (experimental)
    private boolean pttButtonHeld = false;
    private boolean continuousPTTMode = false;
    private StringBuilder accumulatedResults = new StringBuilder();

    // Store config for continuous restart
    private String lastLanguage;
    private int lastMaxResults;
    private String lastPrompt;
    private boolean lastPartialResults;
    private int lastAllowForSilence;

    @Override
    public void load() {
        super.load();
        bridge
            .getWebView()
            .post(() -> {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(bridge.getActivity());
                SpeechRecognitionListener listener = new SpeechRecognitionListener();
                speechRecognizer.setRecognitionListener(listener);
                Logger.info(getLogTag(), "Instantiated SpeechRecognizer in load()");
            });
    }

    @PluginMethod
    public void available(PluginCall call) {
        boolean val = SpeechRecognizer.isRecognitionAvailable(bridge.getContext());
        call.resolve(new JSObject().put("available", val));
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!SpeechRecognizer.isRecognitionAvailable(bridge.getContext())) {
            Logger.warn(TAG, "start() called but speech recognizer unavailable");
            call.unavailable(NOT_AVAILABLE);
            return;
        }

        if (getPermissionState(SPEECH_RECOGNITION) != PermissionState.GRANTED) {
            Logger.warn(TAG, "start() missing RECORD_AUDIO permission");
            call.reject(MISSING_PERMISSION);
            return;
        }

        String language = call.getString("language", Locale.getDefault().toString());
        int maxResults = call.getInt("maxResults", MAX_RESULTS);
        String prompt = call.getString("prompt", null);
        boolean partialResults = call.getBoolean("partialResults", false);
        boolean popup = call.getBoolean("popup", false);
        int allowForSilence = call.getInt("allowForSilence", 0);
        boolean continuousPTT = call.getBoolean("continuousPTT", false);

        Logger.info(
            TAG,
            String.format(
                "Starting recognition | lang=%s maxResults=%d partial=%s popup=%s allowForSilence=%d continuousPTT=%s",
                language,
                maxResults,
                partialResults,
                popup,
                allowForSilence,
                continuousPTT
            )
        );

        // Store config for potential restarts in continuous PTT mode
        this.continuousPTTMode = continuousPTT;
        this.lastLanguage = language;
        this.lastMaxResults = maxResults;
        this.lastPrompt = prompt;
        this.lastPartialResults = partialResults;
        this.lastAllowForSilence = allowForSilence;

        beginListening(language, maxResults, prompt, partialResults, popup, call, allowForSilence);
    }

    @PluginMethod
    public void stop(final PluginCall call) {
        Logger.info(TAG, "stop() requested");
        try {
            stopListening();
        } catch (Exception ex) {
            call.reject(ex.getLocalizedMessage());
            return;
        }
        call.resolve();
    }

    /**
     * Force stop recognition with timeout fallback.
     * This implements the destroy/recreate pattern for reliable PTT.
     *
     * The Android SpeechRecognizer.stopListening() method doesn't reliably stop
     * audio input in all scenarios. This method provides a fallback that:
     * 1. Tries graceful stopListening() first
     * 2. After a timeout, forces stop by destroying and recreating the recognizer
     * 3. Returns the last cached partial result so no speech is lost
     *
     * @param call Plugin call with optional timeout parameter (default 1500ms)
     */
    @PluginMethod
    public void forceStop(final PluginCall call) {
        int timeout = call.getInt("timeout", 1500);
        Logger.info(TAG, "forceStop() requested with timeout=" + timeout + "ms");

        bridge
            .getWebView()
            .post(() -> {
                try {
                    lock.lock();

                    if (!listening) {
                        Logger.debug(TAG, "forceStop() - not listening, resolving immediately");
                        call.resolve();
                        return;
                    }

                    // Cancel any existing timeout
                    if (forceStopRunnable != null) {
                        pttHandler.removeCallbacks(forceStopRunnable);
                    }

                    // First try graceful stop
                    speechRecognizer.stopListening();
                    Logger.debug(TAG, "forceStop() - called stopListening(), starting timeout");

                    // Set timeout for force cancel with destroy/recreate
                    forceStopRunnable = () -> {
                        try {
                            lock.lock();
                            if (listening) {
                                Logger.debug(TAG, "forceStop() - TIMEOUT! Force stopping with destroy/recreate");

                                // Mark as force-stopped to ignore late callbacks
                                forceStopped = true;

                                // Get cached partial result before destroying
                                JSObject result = new JSObject();
                                result.put("matches", previousPartialResults);
                                result.put("forced", true);

                                // Destroy and recreate
                                speechRecognizer.cancel();
                                speechRecognizer.destroy();
                                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(bridge.getActivity());
                                SpeechRecognitionListener listener = new SpeechRecognitionListener();
                                speechRecognizer.setRecognitionListener(listener);

                                // Emit final result with cached partial
                                notifyListeners(PARTIAL_RESULTS_EVENT, result);

                                // Update state
                                listening(false);
                                forceStopped = false;

                                // Emit stopped state
                                JSObject stateResult = new JSObject();
                                stateResult.put("status", "stopped");
                                notifyListeners(LISTENING_EVENT, stateResult);

                                Logger.debug(TAG, "forceStop() - Recognizer destroyed and recreated");
                            }
                        } catch (Exception ex) {
                            Logger.error(TAG, "forceStop() timeout handler error", ex);
                        } finally {
                            lock.unlock();
                        }
                    };
                    pttHandler.postDelayed(forceStopRunnable, timeout);

                    call.resolve();
                } catch (Exception ex) {
                    Logger.error(TAG, "forceStop() error", ex);
                    call.reject(ex.getLocalizedMessage());
                } finally {
                    lock.unlock();
                }
            });
    }

    /**
     * Get the last cached partial result.
     * Useful for retrieving what was heard before a force stop.
     *
     * @param call Plugin call
     */
    @PluginMethod
    public void getLastPartialResult(final PluginCall call) {
        try {
            lock.lock();
            JSObject result = new JSObject();

            if (previousPartialResults != null && previousPartialResults.length() > 0) {
                result.put("text", previousPartialResults.getString(0));
                result.put("matches", previousPartialResults);
                result.put("available", true);
            } else {
                result.put("available", false);
                result.put("text", "");
            }

            call.resolve(result);
        } catch (Exception ex) {
            call.reject(ex.getLocalizedMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Set PTT button state for continuous PTT mode.
     * When held=true and continuousPTT is enabled, recognition will auto-restart on silence.
     *
     * @param call Plugin call with 'held' boolean parameter
     */
    @PluginMethod
    public void setPTTState(final PluginCall call) {
        boolean held = call.getBoolean("held", false);
        Logger.debug(TAG, "setPTTState() held=" + held);

        try {
            lock.lock();
            this.pttButtonHeld = held;

            if (held) {
                // Button pressed - reset accumulated results
                accumulatedResults = new StringBuilder();
                forceStopped = false;
            }

            call.resolve();
        } finally {
            lock.unlock();
        }
    }

    @PluginMethod
    public void getSupportedLanguages(PluginCall call) {
        if (languageReceiver == null) {
            languageReceiver = new Receiver(call);
        }

        List<String> supportedLanguages = languageReceiver.getSupportedLanguages();
        if (supportedLanguages != null) {
            JSONArray languages = new JSONArray(supportedLanguages);
            call.resolve(new JSObject().put("languages", languages));
            return;
        }

        Intent detailsIntent = new Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            detailsIntent.setPackage("com.google.android.googlequicksearchbox");
        }
        bridge.getActivity().sendOrderedBroadcast(detailsIntent, null, languageReceiver, null, Activity.RESULT_OK, null, null);
    }

    @PluginMethod
    public void isListening(PluginCall call) {
        call.resolve(new JSObject().put("listening", listening));
    }

    @PluginMethod
    @Override
    public void checkPermissions(PluginCall call) {
        String state = permissionStateValue(getPermissionState(SPEECH_RECOGNITION));
        call.resolve(new JSObject().put("speechRecognition", state));
    }

    @PluginMethod
    @Override
    public void requestPermissions(PluginCall call) {
        requestPermissionForAlias(SPEECH_RECOGNITION, call, "permissionsCallback");
    }

    @PluginMethod
    public void getPluginVersion(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("version", PLUGIN_VERSION);
        call.resolve(ret);
    }

    @PermissionCallback
    private void permissionsCallback(PluginCall call) {
        String state = permissionStateValue(getPermissionState(SPEECH_RECOGNITION));
        call.resolve(new JSObject().put("speechRecognition", state));
    }

    @ActivityCallback
    private void listeningResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }

        int resultCode = result.getResultCode();
        if (resultCode == Activity.RESULT_OK) {
            try {
                ArrayList<String> matchesList = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                JSObject resultObj = new JSObject();
                resultObj.put("matches", new JSArray(matchesList));
                call.resolve(resultObj);
            } catch (Exception ex) {
                call.reject(ex.getMessage());
            }
        } else {
            call.reject(Integer.toString(resultCode));
        }

        lock.lock();
        listening(false);
        lock.unlock();
    }

    private void beginListening(
        String language,
        int maxResults,
        String prompt,
        final boolean partialResults,
        boolean showPopup,
        PluginCall call,
        int allowForSilence
    ) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, bridge.getActivity().getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults);
        intent.putExtra("android.speech.extra.DICTATION_MODE", partialResults);

        if (allowForSilence > 0) {
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, allowForSilence);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, allowForSilence);
        }

        if (prompt != null) {
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        }

        if (showPopup) {
            startActivityForResult(call, intent, "listeningResult");
        } else {
            bridge
                .getWebView()
                .post(() -> {
                    try {
                        lock.lock();

                        if (speechRecognizer != null) {
                            speechRecognizer.cancel();
                            speechRecognizer.destroy();
                            speechRecognizer = null;
                        }

                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(bridge.getActivity());
                        SpeechRecognitionListener listener = new SpeechRecognitionListener();
                        listener.setCall(call);
                        listener.setPartialResults(partialResults);
                        speechRecognizer.setRecognitionListener(listener);
                        speechRecognizer.startListening(intent);
                        listening(true);
                        if (partialResults) {
                            call.resolve();
                        }
                    } catch (Exception ex) {
                        call.reject(ex.getMessage());
                    } finally {
                        lock.unlock();
                    }
                });
        }
    }

    private void stopListening() {
        bridge
            .getWebView()
            .post(() -> {
                try {
                    lock.lock();
                    if (listening) {
                        Logger.debug(TAG, "Stopping inline recognizer");
                        speechRecognizer.stopListening();
                        listening(false);
                    }
                } catch (Exception ex) {
                    throw ex;
                } finally {
                    lock.unlock();
                }
            });
    }

    private void listening(boolean value) {
        this.listening = value;
    }

    private String permissionStateValue(PermissionState state) {
        switch (state) {
            case GRANTED:
                return "granted";
            case DENIED:
                return "denied";
            case PROMPT:
            case PROMPT_WITH_RATIONALE:
            default:
                return "prompt";
        }
    }

    private class SpeechRecognitionListener implements RecognitionListener {

        private PluginCall call;
        private boolean partialResults;

        public void setCall(PluginCall call) {
            this.call = call;
        }

        public void setPartialResults(boolean partialResults) {
            this.partialResults = partialResults;
        }

        @Override
        public void onReadyForSpeech(Bundle params) {}

        @Override
        public void onBeginningOfSpeech() {
            try {
                lock.lock();
                JSObject ret = new JSObject();
                ret.put("status", "started");
                notifyListeners(LISTENING_EVENT, ret);
                Logger.debug(TAG, "Listening started");
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            bridge
                .getWebView()
                .post(() -> {
                    try {
                        lock.lock();
                        listening(false);

                        JSObject ret = new JSObject();
                        ret.put("status", "stopped");
                        notifyListeners(LISTENING_EVENT, ret);
                    } finally {
                        lock.unlock();
                    }
                });
        }

        @Override
        public void onError(int error) {
            // Ignore if we force-stopped
            if (forceStopped) {
                Logger.debug(TAG, "onError - ignoring (force-stopped): " + error);
                return;
            }

            String errorMssg = getErrorText(error);
            Logger.error(TAG, "Recognizer error: " + errorMssg, null);

            // Check if this is a recoverable error in continuous PTT mode
            boolean isRecoverableError = (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT);

            if (continuousPTTMode && pttButtonHeld && isRecoverableError) {
                Logger.debug(TAG, "onError - Recoverable error in continuous PTT mode, restarting");

                // Accumulate any cached partial result
                if (previousPartialResults != null && previousPartialResults.length() > 0) {
                    try {
                        String lastPartial = previousPartialResults.getString(0);
                        if (!lastPartial.isEmpty()) {
                            if (accumulatedResults.length() > 0) {
                                accumulatedResults.append(" ");
                            }
                            accumulatedResults.append(lastPartial);
                        }
                    } catch (Exception ignored) {}
                }

                // Reset state for restart
                listening(false);
                previousPartialResults = new JSONArray();

                // Small delay then restart
                pttHandler.postDelayed(
                    () -> {
                        if (pttButtonHeld && continuousPTTMode) {
                            Logger.debug(TAG, "Restarting recognition after error (continuous PTT)");
                            beginListening(lastLanguage, lastMaxResults, lastPrompt, lastPartialResults, false, null, lastAllowForSilence);
                        }
                    },
                    100
                );
            } else {
                // Normal error handling
                stopListening();

                if (call != null) {
                    call.reject(errorMssg);
                }
            }
        }

        @Override
        public void onResults(Bundle results) {
            // Ignore if we force-stopped
            if (forceStopped) {
                Logger.debug(TAG, "onResults - ignoring (force-stopped)");
                return;
            }

            // Cancel any pending force-stop timeout since we got results normally
            if (forceStopRunnable != null) {
                pttHandler.removeCallbacks(forceStopRunnable);
            }

            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String resultText = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";

            try {
                JSArray jsArray = new JSArray(matches);
                Logger.debug(TAG, "Received final results count=" + (matches == null ? 0 : matches.size()));

                // Check if we should restart in continuous PTT mode
                if (continuousPTTMode && pttButtonHeld) {
                    Logger.debug(TAG, "onResults - Continuous PTT mode, button still held - accumulating and restarting");

                    // Accumulate the result
                    if (!resultText.isEmpty()) {
                        if (accumulatedResults.length() > 0) {
                            accumulatedResults.append(" ");
                        }
                        accumulatedResults.append(resultText);
                    }

                    // Emit accumulated so far
                    JSObject ret = new JSObject();
                    ret.put("matches", jsArray);
                    ret.put("accumulated", accumulatedResults.toString().trim());
                    ret.put("isRestarting", true);
                    notifyListeners(PARTIAL_RESULTS_EVENT, ret);

                    // Reset state for restart
                    listening(false);
                    previousPartialResults = new JSONArray();

                    // Small delay then restart
                    pttHandler.postDelayed(
                        () -> {
                            if (pttButtonHeld && continuousPTTMode) {
                                Logger.debug(TAG, "Restarting recognition (continuous PTT)");
                                beginListening(
                                    lastLanguage,
                                    lastMaxResults,
                                    lastPrompt,
                                    lastPartialResults,
                                    false,
                                    null,
                                    lastAllowForSilence
                                );
                            }
                        },
                        100
                    );
                } else {
                    // Normal behavior - emit final result
                    if (call != null) {
                        if (!partialResults) {
                            call.resolve(new JSObject().put("status", "success").put("matches", jsArray));
                        } else {
                            JSObject ret = new JSObject();
                            ret.put("matches", jsArray);

                            // Include accumulated if we were in continuous mode
                            if (accumulatedResults.length() > 0) {
                                String finalText = accumulatedResults.toString().trim();
                                if (!resultText.isEmpty()) {
                                    finalText = finalText + " " + resultText;
                                }
                                ret.put("accumulatedText", finalText);
                            }

                            notifyListeners(PARTIAL_RESULTS_EVENT, ret);
                        }
                    }

                    listening(false);
                }
            } catch (Exception ex) {
                if (call != null) {
                    call.resolve(new JSObject().put("status", "error").put("message", ex.getMessage()));
                }
            }
        }

        @Override
        public void onPartialResults(Bundle partialResultsBundle) {
            // Ignore if we force-stopped
            if (forceStopped) {
                return;
            }

            ArrayList<String> matches = partialResultsBundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            JSArray matchesJSON = new JSArray(matches);

            try {
                if (matches != null && matches.size() > 0 && !previousPartialResults.equals(matchesJSON)) {
                    previousPartialResults = matchesJSON;
                    JSObject ret = new JSObject();
                    ret.put("matches", previousPartialResults);
                    notifyListeners(PARTIAL_RESULTS_EVENT, ret);
                    Logger.debug(TAG, "Partial results updated");
                }
            } catch (Exception ex) {}
        }

        @Override
        public void onSegmentResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches == null) {
                return;
            }
            try {
                JSObject ret = new JSObject();
                ret.put("matches", new JSArray(matches));
                notifyListeners(SEGMENT_RESULTS_EVENT, ret);
                Logger.debug(TAG, "Segment results emitted");
            } catch (Exception ignored) {}
        }

        @Override
        public void onEndOfSegmentedSession() {
            notifyListeners(END_OF_SEGMENT_EVENT, new JSObject());
            Logger.debug(TAG, "Segmented session ended");
        }

        @Override
        public void onEvent(int eventType, Bundle params) {}
    }

    private String getErrorText(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "No match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "RecognitionService busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "Error from server";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech input";
            default:
                return "Didn't understand, please try again.";
        }
    }
}
