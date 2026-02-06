package app.capgo.speechrecognition;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
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
        Logger.info(
            TAG,
            String.format(
                "Starting recognition | lang=%s maxResults=%d partial=%s popup=%s allowForSilence=%d",
                language,
                maxResults,
                partialResults,
                popup,
                allowForSilence
            )
        );
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

        try {
            lock.lock();
            resetPartialResultsCache();
        } finally {
            lock.unlock();
        }

        if (showPopup) {
            bridge
                .getActivity()
                .runOnUiThread(() -> {
                    try {
                        SpeechRecognitionPlugin.this.lock.lock();
                        SpeechRecognitionPlugin.this.listening(true);
                        SpeechRecognitionPlugin.this.lock.unlock();
                        SpeechRecognitionPlugin.this.startActivityForResult(call, intent, "listeningResult");
                    } catch (Exception ex) {
                        SpeechRecognitionPlugin.this.lock.lock();
                        SpeechRecognitionPlugin.this.listening(false);
                        SpeechRecognitionPlugin.this.lock.unlock();
                        call.reject(ex.getMessage());
                    }
                });
            return;
        }

        bridge
            .getWebView()
            .post(() -> {
                try {
                    SpeechRecognitionPlugin.this.lock.lock();
                    Logger.info(getLogTag(), "Rebuilding and starting recognizer");
                    rebuildRecognizerLocked(call, partialResults);
                    speechRecognizer.startListening(intent);
                    SpeechRecognitionPlugin.this.listening(true);
                    if (partialResults) {
                        call.resolve();
                    }
                } catch (Exception ex) {
                    Logger.error(getLogTag(), "Error starting listening: " + ex.getMessage(), ex);
                    call.reject(ex.getMessage());
                } finally {
                    SpeechRecognitionPlugin.this.lock.unlock();
                }
            });
    }

    private void stopListening() {
        bridge
            .getWebView()
            .post(() -> {
                try {
                    SpeechRecognitionPlugin.this.lock.lock();
                    Logger.info(getLogTag(), "Stopping listening");
                    if (speechRecognizer != null) {
                        try {
                            speechRecognizer.stopListening();
                        } catch (Exception ignored) {}
                        try {
                            speechRecognizer.cancel();
                        } catch (Exception ignored) {}
                        // Don't destroy here - let rebuildRecognizerLocked handle cleanup
                    }
                    resetPartialResultsCache();
                    SpeechRecognitionPlugin.this.listening(false);
                } finally {
                    SpeechRecognitionPlugin.this.lock.unlock();
                }
            });
    }

    private void destroyRecognizer() {
        bridge.getWebView().post(() -> {
            try {
                SpeechRecognitionPlugin.this.lock.lock();
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                    speechRecognizer = null;
                }
            } finally {
                SpeechRecognitionPlugin.this.lock.unlock();
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        destroyRecognizer();
    }

    private void listening(boolean value) {
        this.listening = value;
    }

    private void resetPartialResultsCache() {
        previousPartialResults = new JSONArray();
    }

    private void rebuildRecognizerLocked(PluginCall call, boolean partialResults) {
        // Reuse the existing recognizer if available - destroying/recreating causes ERROR_SERVER_DISCONNECTED (11)
        // Only create new if null (first time or after an error destroyed it)
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(bridge.getActivity());
            Logger.info(getLogTag(), "Created new SpeechRecognizer instance");
        } else {
            // Cancel any pending recognition before starting a new one
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {}
            Logger.info(getLogTag(), "Reusing existing SpeechRecognizer instance");
        }

        SpeechRecognitionListener listener = new SpeechRecognitionListener();
        listener.setCall(call);
        listener.setPartialResults(partialResults);
        speechRecognizer.setRecognitionListener(listener);
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
            String errorMssg = getErrorText(error);

            // Reset state synchronously on the same thread with proper synchronization
            try {
                lock.lock();
                resetPartialResultsCache();
                SpeechRecognitionPlugin.this.listening(false);

                // Destroy the recognizer to ensure clean state for next attempt
                if (speechRecognizer != null) {
                    try {
                        speechRecognizer.cancel();
                    } catch (Exception ignored) {}
                    try {
                        speechRecognizer.destroy();
                    } catch (Exception ignored) {}
                    speechRecognizer = null;
                }
            } finally {
                lock.unlock();
            }

            Logger.error(TAG, "Recognizer error: " + errorMssg, null);

            if (call != null) {
                call.reject(errorMssg);
            }
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

            try {
                JSArray jsArray = new JSArray(matches);
                Logger.debug(TAG, "Received final results count=" + (matches == null ? 0 : matches.size()));

                if (call != null) {
                    if (!partialResults) {
                        call.resolve(new JSObject().put("status", "success").put("matches", jsArray));
                    } else {
                        JSObject ret = new JSObject();
                        ret.put("matches", jsArray);
                        notifyListeners(PARTIAL_RESULTS_EVENT, ret);
                    }
                }
            } catch (Exception ex) {
                if (call != null) {
                    call.resolve(new JSObject().put("status", "error").put("message", ex.getMessage()));
                }
            } finally {
                try {
                    lock.lock();
                    resetPartialResultsCache();
                } finally {
                    lock.unlock();
                }
            }
        }

        @Override
        public void onPartialResults(Bundle partialResultsBundle) {
            ArrayList<String> matches = partialResultsBundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            JSArray matchesJSON = new JSArray(matches);

            try {
                lock.lock();
                if (matches != null && matches.size() > 0 && !previousPartialResults.equals(matchesJSON)) {
                    previousPartialResults = matchesJSON;
                    JSObject ret = new JSObject();
                    ret.put("matches", previousPartialResults);
                    notifyListeners(PARTIAL_RESULTS_EVENT, ret);
                    Logger.debug(TAG, "Partial results updated");
                }
            } catch (Exception ex) {
            } finally {
                lock.unlock();
            }
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
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "Server disconnected";
            default:
                return "Didn't understand, please try again. Error code: " + errorCode;
        }
    }
}
