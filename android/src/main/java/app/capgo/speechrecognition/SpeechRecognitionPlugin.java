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

    // State machine
    private enum ListeningState {
        IDLE,
        STARTING,
        STARTED,
        STOPPING
    }

    private Receiver languageReceiver;
    private SpeechRecognizer speechRecognizer;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean listening = false;
    private JSONArray previousPartialResults = new JSONArray();
    
    // State machine tracking
    private ListeningState state = ListeningState.IDLE;
    private long sessionId = 0;
    private boolean readyForNext = true;

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

        lock.lock();
        try {
            // Increment session ID and set state to STARTING
            sessionId++;
            state = ListeningState.STARTING;
            long currentSessionId = sessionId;

            // Immediately emit startingListening event
            JSObject startingPayload = new JSObject();
            startingPayload.put("state", "startingListening");
            startingPayload.put("sessionId", currentSessionId);
            startingPayload.put("reason", "userStart");
            notifyListeners(LISTENING_EVENT, startingPayload);

            String language = call.getString("language", Locale.getDefault().toString());
            int maxResults = call.getInt("maxResults", MAX_RESULTS);
            String prompt = call.getString("prompt", null);
            boolean partialResults = call.getBoolean("partialResults", false);
            boolean popup = call.getBoolean("popup", false);
            int allowForSilence = call.getInt("allowForSilence", 0);
            
            Logger.info(
                TAG,
                String.format(
                    "Starting recognition | sessionId=%d lang=%s maxResults=%d partial=%s popup=%s allowForSilence=%d",
                    currentSessionId,
                    language,
                    maxResults,
                    partialResults,
                    popup,
                    allowForSilence
                )
            );
            
            beginListening(language, maxResults, prompt, partialResults, popup, call, allowForSilence, currentSessionId);
        } finally {
            lock.unlock();
        }
    }

    @PluginMethod
    public void stop(final PluginCall call) {
        Logger.info(TAG, "stop() requested");
        
        lock.lock();
        try {
            if (state == ListeningState.IDLE) {
                Logger.debug(TAG, "stop() called but already IDLE");
                call.resolve();
                return;
            }

            state = ListeningState.STOPPING;
            long currentSessionId = sessionId;
            
            // Emit stoppingListening event
            JSObject stoppingPayload = new JSObject();
            stoppingPayload.put("state", "stoppingListening");
            stoppingPayload.put("sessionId", currentSessionId);
            stoppingPayload.put("reason", "userStop");
            notifyListeners(LISTENING_EVENT, stoppingPayload);
            
            stopListening();
            call.resolve();
        } catch (Exception ex) {
            call.reject(ex.getLocalizedMessage());
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
        int allowForSilence,
        long currentSessionId
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
                        SpeechRecognitionListener listener = new SpeechRecognitionListener(currentSessionId);
                        listener.setCall(call);
                        listener.setPartialResults(partialResults);
                        speechRecognizer.setRecognitionListener(listener);
                        speechRecognizer.startListening(intent);
                        listening(true);
                        
                        // Immediately emit 'started' event after startListening succeeds
                        // This guarantees the UI gets a started signal even during silence
                        state = ListeningState.STARTED;
                        JSObject startedPayload = new JSObject();
                        startedPayload.put("state", "started");
                        startedPayload.put("sessionId", currentSessionId);
                        startedPayload.put("reason", "userStart");
                        startedPayload.put("status", "started"); // backward compatibility
                        notifyListeners(LISTENING_EVENT, startedPayload);
                        Logger.debug(TAG, "Emitted 'started' state for sessionId=" + currentSessionId);
                        
                        if (partialResults) {
                            call.resolve();
                        }
                    } catch (Exception ex) {
                        // Emit error and finish session on failure
                        JSObject errorPayload = new JSObject();
                        errorPayload.put("code", "START_FAILED");
                        errorPayload.put("message", ex.getMessage());
                        errorPayload.put("sessionId", currentSessionId);
                        notifyListeners(ERROR_EVENT, errorPayload);
                        
                        finishSession(currentSessionId, "error", "START_FAILED");
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

    private void finishSession(long finishedSessionId, String reason, String errorCode) {
        lock.lock();
        try {
            if (finishedSessionId != sessionId) {
                Logger.debug(TAG, "Ignoring stale session finishSession call: " + finishedSessionId + " (current=" + sessionId + ")");
                return;
            }

            Logger.info(TAG, "Finishing session " + finishedSessionId + " reason=" + reason + " errorCode=" + errorCode);

            // Transition to STOPPING state and emit stoppingListening event
            if (state != ListeningState.STOPPING) {
                state = ListeningState.STOPPING;
                
                JSObject stoppingPayload = new JSObject();
                stoppingPayload.put("state", "stoppingListening");
                stoppingPayload.put("sessionId", finishedSessionId);
                stoppingPayload.put("reason", reason);
                if (errorCode != null) {
                    stoppingPayload.put("errorCode", errorCode);
                }
                notifyListeners(LISTENING_EVENT, stoppingPayload);
                Logger.debug(TAG, "Emitted 'stoppingListening' state for sessionId=" + finishedSessionId);
            }

            // Tear down the current recognizer
            if (speechRecognizer != null) {
                try {
                    speechRecognizer.cancel();
                    speechRecognizer.destroy();
                } catch (Exception ignored) {}
                speechRecognizer = null;
            }

            state = ListeningState.IDLE;
            listening(false);
            readyForNext = false;
        } finally {
            lock.unlock();
        }

        // Recreate recognizer on main thread
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(bridge.getActivity());
                SpeechRecognitionListener dummyListener = new SpeechRecognitionListener(sessionId);
                speechRecognizer.setRecognitionListener(dummyListener);
                
                lock.lock();
                try {
                    readyForNext = true;
                } finally {
                    lock.unlock();
                }

                JSObject readyPayload = new JSObject();
                readyPayload.put("sessionId", finishedSessionId);
                notifyListeners(READY_FOR_NEXT_SESSION_EVENT, readyPayload);
                Logger.debug(TAG, "Recognizer recreated, ready for next session");
            } catch (Exception e) {
                Logger.error(TAG, "Failed to recreate recognizer", e);
                JSObject err = new JSObject();
                err.put("code", "RECREATE_FAILED");
                err.put("message", e.getMessage());
                err.put("sessionId", finishedSessionId);
                notifyListeners(ERROR_EVENT, err);
            }

            // Emit final 'stopped' state after teardown+recreate
            JSObject stoppedPayload = new JSObject();
            stoppedPayload.put("state", "stopped");
            stoppedPayload.put("sessionId", finishedSessionId);
            stoppedPayload.put("reason", reason);
            if (errorCode != null) {
                stoppedPayload.put("errorCode", errorCode);
            }
            stoppedPayload.put("status", "stopped"); // backward compatibility
            notifyListeners(LISTENING_EVENT, stoppedPayload);
            Logger.debug(TAG, "Emitted 'stopped' state for sessionId=" + finishedSessionId);
        });
    }

    private String getErrorCode(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "AUDIO";
            case SpeechRecognizer.ERROR_CLIENT:
                return "CLIENT";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "INSUFFICIENT_PERMISSIONS";
            case SpeechRecognizer.ERROR_NETWORK:
                return "NETWORK";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_SERVER:
                return "SERVER";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "SPEECH_TIMEOUT";
            default:
                return "UNKNOWN_" + errorCode;
        }
    }

    private class SpeechRecognitionListener implements RecognitionListener {

        private final long listenerSessionId;
        private PluginCall call;
        private boolean partialResults;

        public SpeechRecognitionListener(long sessionId) {
            this.listenerSessionId = sessionId;
        }

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
            // No longer emit started here — we emit it immediately after startListening()
            // to guarantee the event fires even during silence
            Logger.debug(TAG, "onBeginningOfSpeech callback (session " + listenerSessionId + ")");
        }

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            Logger.debug(TAG, "onEndOfSpeech callback (session " + listenerSessionId + ")");
            // Don't emit stopped here — wait for onResults or onError to finish the session properly
        }

        @Override
        public void onError(int error) {
            lock.lock();
            long currentSessionId = listenerSessionId;
            lock.unlock();

            String errorCode = getErrorCode(error);
            String errorMessage = getErrorText(error);
            Logger.error(TAG, "Recognizer error: " + errorMessage + " (code=" + errorCode + ", session=" + currentSessionId + ")", null);

            // Always emit error event — never swallow errors
            JSObject errorPayload = new JSObject();
            errorPayload.put("code", errorCode);
            errorPayload.put("message", errorMessage);
            errorPayload.put("sessionId", currentSessionId);
            notifyListeners(ERROR_EVENT, errorPayload);

            // Determine reason based on error type
            String reason;
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                reason = "silence";
            } else {
                reason = "error";
            }

            // Finish the session
            finishSession(currentSessionId, reason, errorCode);

            if (call != null && !partialResults) {
                call.reject(errorMessage);
            }
        }

        @Override
        public void onResults(Bundle results) {
            lock.lock();
            long currentSessionId = listenerSessionId;
            lock.unlock();

            ArrayList<String> matches = buildMatchesWithUnstableText(results);

            try {
                JSArray jsArray = new JSArray(matches);
                Logger.debug(TAG, "Received final results count=" + (matches == null ? 0 : matches.size()) + " (session " + currentSessionId + ")");

                if (call != null) {
                    if (!partialResults) {
                        call.resolve(new JSObject().put("status", "success").put("matches", jsArray));
                    } else {
                        JSObject ret = new JSObject();
                        ret.put("matches", jsArray);
                        notifyListeners(PARTIAL_RESULTS_EVENT, ret);
                    }
                }

                // Finish session with reason="results"
                finishSession(currentSessionId, "results", null);
            } catch (Exception ex) {
                Logger.error(TAG, "Error processing results", ex);
                if (call != null) {
                    call.resolve(new JSObject().put("status", "error").put("message", ex.getMessage()));
                }
                finishSession(currentSessionId, "error", "RESULTS_PROCESSING_FAILED");
            }
        }

        @Override
        public void onPartialResults(Bundle partialResultsBundle) {
            ArrayList<String> matches = buildMatchesWithUnstableText(partialResultsBundle);
            if (matches == null || matches.isEmpty()) {
                return;
            }

            try {
                JSArray matchesJSON = new JSArray(matches);
                if (!previousPartialResults.equals(matchesJSON)) {
                    previousPartialResults = matchesJSON;
                    JSObject ret = new JSObject();
                    ret.put("matches", previousPartialResults);
                    notifyListeners(PARTIAL_RESULTS_EVENT, ret);
                    Logger.debug(TAG, "Partial results updated");
                }
            } catch (Exception ex) {
                Logger.error(TAG, "onPartialResults failed", ex);
            }
        }

        private ArrayList<String> buildMatchesWithUnstableText(Bundle resultsBundle) {
            ArrayList<String> matches = resultsBundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches == null || matches.isEmpty()) {
                return matches;
            }

            String unstableText = resultsBundle.getString("android.speech.extra.UNSTABLE_TEXT");
            if (unstableText != null) {
                String trimmedUnstable = unstableText.trim();
                if (trimmedUnstable.isEmpty()) {
                    return matches;
                }

                String firstMatch = matches.get(0);
                if (firstMatch == null) {
                    return matches;
                }

                String trimmedFirstMatch = firstMatch.trim();
                if (trimmedFirstMatch.equals(trimmedUnstable) || trimmedFirstMatch.endsWith(" " + trimmedUnstable)) {
                    return matches;
                }

                ArrayList<String> mergedMatches = new ArrayList<>(matches);
                mergedMatches.set(0, trimmedFirstMatch + " " + trimmedUnstable);
                return mergedMatches;
            }

            return matches;
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
