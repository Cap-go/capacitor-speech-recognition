package app.capgo.speechrecognition;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
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
import java.util.concurrent.Executor;
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
    private boolean speechRecognizerUsesOnDevice = false;
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
                speechRecognizerUsesOnDevice = false;
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
    public void isOnDeviceRecognitionAvailable(PluginCall call) {
        String language = call.getString("language", Locale.getDefault().toLanguageTag());
        if (!canUseOnDeviceRecognition()) {
            call.resolve(new JSObject().put("available", false));
            return;
        }

        final SpeechRecognizer supportChecker;
        try {
            supportChecker = SpeechRecognizer.createOnDeviceSpeechRecognizer(bridge.getActivity());
        } catch (UnsupportedOperationException ex) {
            call.resolve(new JSObject().put("available", false));
            return;
        }

        Intent intent = buildRecognizerIntent(language, MAX_RESULTS, null, false, 0, true);
        supportChecker.checkRecognitionSupport(
            intent,
            mainExecutor(),
            new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport support) {
                    boolean available =
                        isLanguageSupported(language, support.getInstalledOnDeviceLanguages()) ||
                        isLanguageSupported(language, support.getSupportedOnDeviceLanguages()) ||
                        isLanguageSupported(language, support.getPendingOnDeviceLanguages());
                    supportChecker.destroy();
                    call.resolve(new JSObject().put("available", available));
                }

                @Override
                public void onError(int error) {
                    Logger.warn(TAG, "On-device recognition support check failed: " + getErrorText(error));
                    supportChecker.destroy();
                    call.resolve(new JSObject().put("available", false));
                }
            }
        );
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

        String language = call.getString("language", Locale.getDefault().toLanguageTag());
        int maxResults = call.getInt("maxResults", MAX_RESULTS);
        String prompt = call.getString("prompt", null);
        boolean partialResults = call.getBoolean("partialResults", false);
        boolean popup = call.getBoolean("popup", false);
        boolean useOnDeviceRecognition = call.getBoolean("useOnDeviceRecognition", false);
        int allowForSilence = call.getInt("allowForSilence", 0);

        if (useOnDeviceRecognition && popup) {
            call.reject("On-device recognition is not supported with popup mode on Android.");
            return;
        }

        if (useOnDeviceRecognition && !canUseOnDeviceRecognition()) {
            call.unavailable("On-device speech recognition is not available on this device.");
            return;
        }

        Logger.info(
            TAG,
            String.format(
                "Starting recognition | lang=%s maxResults=%d partial=%s popup=%s onDevice=%s allowForSilence=%d",
                language,
                maxResults,
                partialResults,
                popup,
                useOnDeviceRecognition,
                allowForSilence
            )
        );

        beginListening(language, maxResults, prompt, partialResults, popup, call, allowForSilence, useOnDeviceRecognition);
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

        try {
            lock.lock();
            resetPartialResultsCache();
            listening(false);
        } finally {
            lock.unlock();
        }
    }

    private void beginListening(
        String language,
        int maxResults,
        String prompt,
        final boolean partialResults,
        boolean showPopup,
        PluginCall call,
        int allowForSilence,
        boolean useOnDeviceRecognition
    ) {
        Intent intent = buildRecognizerIntent(language, maxResults, prompt, partialResults, allowForSilence, useOnDeviceRecognition);

        if (showPopup) {
            startActivityForResult(call, intent, "listeningResult");
            return;
        }

        bridge
            .getWebView()
            .post(() -> {
                try {
                    lock.lock();
                    resetPartialResultsCache();
                    rebuildRecognizerLocked(call, partialResults, useOnDeviceRecognition);

                    if (useOnDeviceRecognition) {
                        beginOnDeviceListening(intent, language, partialResults, call);
                    } else {
                        startInlineListening(intent, partialResults, call);
                    }
                } catch (Exception ex) {
                    Logger.error(getLogTag(), "Error starting listening: " + ex.getMessage(), ex);
                    call.reject(ex.getMessage());
                } finally {
                    lock.unlock();
                }
            });
    }

    private Intent buildRecognizerIntent(
        String language,
        int maxResults,
        String prompt,
        boolean partialResults,
        int allowForSilence,
        boolean useOnDeviceRecognition
    ) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, bridge.getActivity().getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults);
        intent.putExtra("android.speech.extra.DICTATION_MODE", partialResults);

        if (useOnDeviceRecognition) {
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        }

        if (allowForSilence > 0) {
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, allowForSilence);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, allowForSilence);
        }

        if (prompt != null) {
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        }

        return intent;
    }

    private void rebuildRecognizerLocked(PluginCall call, boolean partialResults, boolean useOnDeviceRecognition) {
        if (speechRecognizer != null && speechRecognizerUsesOnDevice != useOnDeviceRecognition) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {}
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }

        if (speechRecognizer == null) {
            speechRecognizer = useOnDeviceRecognition
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(bridge.getActivity())
                : SpeechRecognizer.createSpeechRecognizer(bridge.getActivity());
            speechRecognizerUsesOnDevice = useOnDeviceRecognition;
            Logger.info(getLogTag(), "Created new SpeechRecognizer instance");
        } else {
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

    private void beginOnDeviceListening(Intent intent, String language, boolean partialResults, PluginCall call) {
        speechRecognizer.checkRecognitionSupport(
            intent,
            mainExecutor(),
            new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport support) {
                    boolean installed = isLanguageSupported(language, support.getInstalledOnDeviceLanguages());
                    boolean supported =
                        installed ||
                        isLanguageSupported(language, support.getSupportedOnDeviceLanguages()) ||
                        isLanguageSupported(language, support.getPendingOnDeviceLanguages());

                    if (!supported) {
                        call.reject("On-device recognition is not available for language: " + language);
                        return;
                    }

                    if (installed) {
                        startInlineListening(intent, partialResults, call);
                        return;
                    }

                    triggerOnDeviceModelDownload(intent, partialResults, call);
                }

                @Override
                public void onError(int error) {
                    call.reject(getErrorText(error));
                }
            }
        );
    }

    private void triggerOnDeviceModelDownload(Intent intent, boolean partialResults, PluginCall call) {
        speechRecognizer.triggerModelDownload(
            intent,
            mainExecutor(),
            new android.speech.ModelDownloadListener() {
                @Override
                public void onProgress(int completedPercent) {}

                @Override
                public void onSuccess() {
                    startInlineListening(intent, partialResults, call);
                }

                @Override
                public void onScheduled() {
                    call.reject("On-device speech model download was scheduled. Try again once it finishes.");
                }

                @Override
                public void onError(int error) {
                    call.reject(getErrorText(error));
                }
            }
        );
    }

    private void startInlineListening(Intent intent, boolean partialResults, PluginCall call) {
        speechRecognizer.startListening(intent);
        listening(true);
        if (partialResults) {
            call.resolve();
        }
    }

    private void stopListening() {
        bridge
            .getWebView()
            .post(() -> {
                try {
                    lock.lock();
                    Logger.info(getLogTag(), "Stopping listening");
                    if (speechRecognizer != null) {
                        try {
                            speechRecognizer.stopListening();
                        } catch (Exception ignored) {}
                        try {
                            speechRecognizer.cancel();
                        } catch (Exception ignored) {}
                    }
                    resetPartialResultsCache();
                    listening(false);
                } finally {
                    lock.unlock();
                }
            });
    }

    private void destroyRecognizer() {
        bridge
            .getWebView()
            .post(() -> {
                try {
                    lock.lock();
                    if (speechRecognizer != null) {
                        try {
                            speechRecognizer.destroy();
                        } catch (Exception ignored) {}
                        speechRecognizer = null;
                    }
                    speechRecognizerUsesOnDevice = false;
                } finally {
                    lock.unlock();
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

    private boolean canUseOnDeviceRecognition() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(bridge.getContext());
    }

    private boolean isLanguageSupported(String requestedLanguage, List<String> candidateLanguages) {
        if (candidateLanguages == null || candidateLanguages.isEmpty()) {
            return false;
        }

        String normalizedRequestedLanguage = normalizeLanguageTag(requestedLanguage);
        for (String candidateLanguage : candidateLanguages) {
            if (normalizedRequestedLanguage.equals(normalizeLanguageTag(candidateLanguage))) {
                return true;
            }
        }

        return false;
    }

    private String normalizeLanguageTag(String language) {
        return language == null ? "" : language.replace('_', '-').toLowerCase(Locale.US);
    }

    private Executor mainExecutor() {
        return command -> bridge.getActivity().runOnUiThread(command);
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

            try {
                lock.lock();
                resetPartialResultsCache();
                listening(false);

                if (speechRecognizer != null) {
                    try {
                        speechRecognizer.cancel();
                    } catch (Exception ignored) {}
                    try {
                        speechRecognizer.destroy();
                    } catch (Exception ignored) {}
                    speechRecognizer = null;
                }
                speechRecognizerUsesOnDevice = false;
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
            ArrayList<String> matches = buildMatchesWithUnstableText(results);

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
            ArrayList<String> matches = buildMatchesWithUnstableText(partialResultsBundle);
            if (matches == null || matches.isEmpty()) {
                return;
            }

            try {
                lock.lock();
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
            } finally {
                lock.unlock();
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
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "Server disconnected";
            default:
                return "Didn't understand, please try again. Error code: " + errorCode;
        }
    }
}
