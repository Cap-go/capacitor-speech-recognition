package app.capgo.speechrecognition;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.ModelDownloadListener;
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
    private static final String PLUGIN_VERSION = "8.0.10";
    private static final int FORCE_STOP_TIMEOUT_MS = 1500;
    private static final int STOP_FALLBACK_TIMEOUT_MS = 500;
    private static final int CONTINUOUS_RESTART_DELAY_MS = 100;

    private enum ListeningState {
        IDLE,
        STARTING,
        STARTED,
        STOPPING
    }

    private Receiver languageReceiver;
    private SpeechRecognizer speechRecognizer;
    private boolean speechRecognizerUsesOnDevice = false;
    private final ReentrantLock lock = new ReentrantLock();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean listening = false;
    private JSONArray previousPartialResults = new JSONArray();

    private Runnable forceStopRunnable;
    private boolean forceStopped = false;
    private boolean pttButtonHeld = false;
    private boolean continuousPTTMode = false;
    private boolean muteRecognizerBeep = false;
    private Integer savedNotificationVolume;
    private Integer savedSystemVolume;
    private long mutedForGeneration = -1;
    private boolean popupSessionActive = false;
    private boolean popupSessionCancelled = false;
    private StringBuilder accumulatedResults = new StringBuilder();

    private String lastLanguage = Locale.getDefault().toLanguageTag();
    private int lastMaxResults = MAX_RESULTS;
    private String lastPrompt;
    private boolean lastPartialResults = false;
    private int lastAllowForSilence = 0;
    private boolean lastUseOnDeviceRecognition = false;

    private PluginCall activeStartCall;
    private ListeningState state = ListeningState.IDLE;
    private long sessionId = 0;
    private long recognizerGeneration = 0;
    private String pendingStopReason;

    @Override
    public void load() {
        super.load();
        bridge
            .getWebView()
            .post(() -> {
                try {
                    lock.lock();
                    recreateIdleRecognizerLocked();
                    Logger.info(getLogTag(), "Instantiated SpeechRecognizer in load()");
                } finally {
                    lock.unlock();
                }
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
            call.unavailable(NOT_AVAILABLE);
            return;
        }

        if (getPermissionState(SPEECH_RECOGNITION) != PermissionState.GRANTED) {
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
        boolean continuousPTT = call.getBoolean("continuousPTT", false);
        boolean muteRecognizerBeepOption = call.getBoolean("muteRecognizerBeep", continuousPTT);

        if (useOnDeviceRecognition && popup) {
            call.reject("On-device recognition is not supported with popup mode on Android.");
            return;
        }

        if (continuousPTT && popup) {
            call.reject("continuousPTT is only supported with inline recognition on Android.");
            return;
        }

        if (continuousPTT && !partialResults) {
            call.reject("continuousPTT requires partialResults: true.");
            return;
        }

        if (useOnDeviceRecognition && !canUseOnDeviceRecognition()) {
            call.unavailable("On-device speech recognition is not available on this device.");
            return;
        }

        final long currentSessionId;
        try {
            lock.lock();
            if (state != ListeningState.IDLE) {
                call.reject("Speech recognition is already running.");
                return;
            }
            cancelPendingForceStopLocked();
            forceStopped = false;
            pendingStopReason = null;
            resetPartialResultsCache();
            accumulatedResults = new StringBuilder();
            continuousPTTMode = continuousPTT;
            muteRecognizerBeep = muteRecognizerBeepOption;
            popupSessionActive = false;
            popupSessionCancelled = false;
            lastLanguage = language;
            lastMaxResults = maxResults;
            lastPrompt = prompt;
            lastPartialResults = partialResults;
            lastAllowForSilence = allowForSilence;
            lastUseOnDeviceRecognition = useOnDeviceRecognition;
            activeStartCall = null;
            sessionId++;
            currentSessionId = sessionId;
            state = ListeningState.STARTING;
        } finally {
            lock.unlock();
        }

        emitListeningState("startingListening", currentSessionId, "userStart", null, null);

        Logger.info(
            TAG,
            String.format(
                Locale.US,
                "Starting recognition | sessionId=%d lang=%s maxResults=%d partial=%s popup=%s onDevice=%s allowForSilence=%d continuousPTT=%s",
                currentSessionId,
                language,
                maxResults,
                partialResults,
                popup,
                useOnDeviceRecognition,
                allowForSilence,
                continuousPTT
            )
        );

        beginListening(
            language,
            maxResults,
            prompt,
            partialResults,
            popup,
            call,
            allowForSilence,
            useOnDeviceRecognition,
            currentSessionId,
            false
        );
    }

    @PluginMethod
    public void stop(PluginCall call) {
        final long currentSessionId;
        final boolean popupActive;
        PluginCall popupStartCall = null;
        try {
            lock.lock();
            if (state == ListeningState.IDLE && !listening) {
                call.resolve();
                return;
            }

            currentSessionId = sessionId;
            pendingStopReason = "userStop";
            if (state != ListeningState.STOPPING) {
                state = ListeningState.STOPPING;
                emitListeningState("stoppingListening", currentSessionId, "userStop", null, null);
            }

            popupActive = popupSessionActive;
            if (popupActive) {
                cancelPendingForceStopLocked();
                popupSessionCancelled = true;
                popupStartCall = activeStartCall;
                activeStartCall = null;
            }
        } finally {
            lock.unlock();
        }

        if (popupStartCall != null) {
            popupStartCall.reject("Recognition stopped before final results were produced.");
        }

        if (popupActive) {
            call.resolve();
            return;
        }

        bridge
            .getWebView()
            .post(() -> {
                try {
                    lock.lock();
                    cancelPendingForceStopLocked();
                    if (speechRecognizer != null) {
                        try {
                            speechRecognizer.stopListening();
                        } catch (Exception ignored) {}
                    }
                    listening(false);
                    scheduleFinishFallbackLocked(currentSessionId, "userStop", null, STOP_FALLBACK_TIMEOUT_MS);
                } finally {
                    lock.unlock();
                }
            });
        call.resolve();
    }

    @PluginMethod
    public void forceStop(PluginCall call) {
        final int timeout = call.getInt("timeout", FORCE_STOP_TIMEOUT_MS);
        final long currentSessionId;
        final boolean popupActive;
        PluginCall popupStartCall = null;
        try {
            lock.lock();
            if (state == ListeningState.IDLE && !listening) {
                call.resolve();
                return;
            }

            currentSessionId = sessionId;
            pendingStopReason = "forceStop";
            if (state != ListeningState.STOPPING) {
                state = ListeningState.STOPPING;
                emitListeningState("stoppingListening", currentSessionId, "forceStop", null, null);
            }

            popupActive = popupSessionActive;
            if (popupActive) {
                cancelPendingForceStopLocked();
                popupSessionCancelled = true;
                popupStartCall = activeStartCall;
                activeStartCall = null;
            }

            cancelPendingForceStopLocked();
            if (!popupActive && speechRecognizer != null) {
                try {
                    speechRecognizer.stopListening();
                } catch (Exception ignored) {}
            }

            forceStopRunnable = () -> {
                PluginCall startCallToReject = null;
                JSObject forcedPayload = null;
                try {
                    lock.lock();
                    if (currentSessionId != sessionId || (state == ListeningState.IDLE && !listening)) {
                        return;
                    }

                    forceStopped = true;
                    startCallToReject = activeStartCall;
                    activeStartCall = null;
                    forcedPayload = buildForcedPartialResultLocked();
                } finally {
                    lock.unlock();
                }

                if (startCallToReject != null) {
                    startCallToReject.reject("Recognition force stopped before final results were produced.");
                }
                if (forcedPayload != null) {
                    notifyListeners(PARTIAL_RESULTS_EVENT, forcedPayload);
                }
                finishSession(currentSessionId, "forceStop", null);
            };
            if (!popupActive) {
                handler.postDelayed(forceStopRunnable, timeout);
            }
        } finally {
            lock.unlock();
        }

        if (popupStartCall != null) {
            popupStartCall.reject("Recognition force stopped before final results were produced.");
        }

        if (popupActive) {
            call.resolve();
            return;
        }

        call.resolve();
    }

    @PluginMethod
    public void getLastPartialResult(PluginCall call) {
        try {
            lock.lock();
            JSObject result = new JSObject();
            String text = buildCurrentTranscriptTextLocked();
            result.put("available", !text.isEmpty());
            result.put("text", text);
            if (previousPartialResults.length() > 0) {
                result.put("matches", previousPartialResults);
            }
            call.resolve(result);
        } catch (Exception ex) {
            call.reject(ex.getLocalizedMessage());
        } finally {
            lock.unlock();
        }
    }

    @PluginMethod
    public void setPTTState(PluginCall call) {
        boolean held = call.getBoolean("held", false);
        try {
            lock.lock();
            pttButtonHeld = held;
            if (call.getData().has("mute")) {
                muteRecognizerBeep = call.getBoolean("mute");
            }
            if (held) {
                accumulatedResults = new StringBuilder();
                forceStopped = false;
                pendingStopReason = null;
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
        call.resolve(new JSObject().put("version", PLUGIN_VERSION));
    }

    @PermissionCallback
    private void permissionsCallback(PluginCall call) {
        String state = permissionStateValue(getPermissionState(SPEECH_RECOGNITION));
        call.resolve(new JSObject().put("speechRecognition", state));
    }

    @ActivityCallback
    private void listeningResult(PluginCall call, ActivityResult result) {
        long currentSessionId;
        String finalReason;
        boolean popupCancelled;
        try {
            lock.lock();
            currentSessionId = sessionId;
            finalReason = pendingStopReason != null ? pendingStopReason : "results";
            popupCancelled = popupSessionCancelled;
            popupSessionActive = false;
            popupSessionCancelled = false;
            activeStartCall = null;
        } finally {
            lock.unlock();
        }

        if (call != null && !popupCancelled) {
            int resultCode = result.getResultCode();
            if (resultCode == Activity.RESULT_OK) {
                try {
                    ArrayList<String> matchesList = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    call.resolve(new JSObject().put("matches", new JSArray(matchesList)));
                } catch (Exception ex) {
                    call.reject(ex.getMessage());
                }
            } else {
                call.reject(Integer.toString(resultCode));
            }
        }

        finishSession(currentSessionId, finalReason, null);
    }

    private void beginListening(
        String language,
        int maxResults,
        String prompt,
        boolean partialResults,
        boolean showPopup,
        PluginCall call,
        int allowForSilence,
        boolean useOnDeviceRecognition,
        long currentSessionId,
        boolean restarting
    ) {
        Intent intent = buildRecognizerIntent(language, maxResults, prompt, partialResults, allowForSilence, useOnDeviceRecognition);

        if (showPopup) {
            try {
                lock.lock();
                listening(true);
                state = ListeningState.STARTED;
                popupSessionActive = true;
                popupSessionCancelled = false;
                activeStartCall = call;
            } finally {
                lock.unlock();
            }
            emitListeningState("started", currentSessionId, restarting ? "results" : "userStart", null, "started");
            startActivityForResult(call, intent, "listeningResult");
            return;
        }

        bridge
            .getWebView()
            .post(() -> {
                try {
                    lock.lock();
                    if (currentSessionId != sessionId) {
                        return;
                    }

                    resetPartialResultsCache();
                    rebuildRecognizerLocked(call, partialResults, useOnDeviceRecognition, currentSessionId);
                    if (!partialResults && call != null) {
                        activeStartCall = call;
                    }

                    if (useOnDeviceRecognition) {
                        beginOnDeviceListening(intent, language, partialResults, call, currentSessionId, restarting);
                    } else {
                        startInlineListening(intent, partialResults, call, currentSessionId, restarting);
                    }
                } catch (Exception ex) {
                    Logger.error(TAG, "Error starting listening", ex);
                    if (call != null) {
                        call.reject(ex.getMessage());
                    }
                    emitErrorEvent("START_FAILED", ex.getMessage(), currentSessionId);
                    finishSession(currentSessionId, "error", "START_FAILED");
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

        // EXTRA_DICTATE_BEEP is undocumented and ignored on some devices; AudioManager fallback handles those.
        if (muteRecognizerBeep) {
            intent.putExtra(EXTRA_DICTATE_BEEP, false);
        }

        return intent;
    }

    private void rebuildRecognizerLocked(PluginCall call, boolean partialResults, boolean useOnDeviceRecognition, long currentSessionId) {
        if (speechRecognizer != null && speechRecognizerUsesOnDevice != useOnDeviceRecognition) {
            destroyCurrentRecognizerLocked();
        }

        if (speechRecognizer == null) {
            speechRecognizer = useOnDeviceRecognition
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(bridge.getActivity())
                : SpeechRecognizer.createSpeechRecognizer(bridge.getActivity());
            speechRecognizerUsesOnDevice = useOnDeviceRecognition;
        } else {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {}
            speechRecognizerUsesOnDevice = useOnDeviceRecognition;
        }

        recognizerGeneration++;
        SpeechRecognitionListener listener = new SpeechRecognitionListener(currentSessionId, recognizerGeneration);
        listener.setCall(call);
        listener.setPartialResults(partialResults);
        speechRecognizer.setRecognitionListener(listener);
    }

    private void beginOnDeviceListening(
        Intent intent,
        String language,
        boolean partialResults,
        PluginCall call,
        long currentSessionId,
        boolean restarting
    ) {
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
                        if (call != null) {
                            call.reject("On-device recognition is not available for language: " + language);
                        }
                        emitErrorEvent(
                            "UNSUPPORTED_LOCALE",
                            "On-device recognition is not available for language: " + language,
                            currentSessionId
                        );
                        finishSession(currentSessionId, "error", "UNSUPPORTED_LOCALE");
                        return;
                    }

                    if (installed) {
                        startInlineListening(intent, partialResults, call, currentSessionId, restarting);
                        return;
                    }

                    triggerOnDeviceModelDownload(intent, partialResults, call, currentSessionId, restarting);
                }

                @Override
                public void onError(int error) {
                    String errorCode = getErrorCode(error);
                    String message = getErrorText(error);
                    if (call != null) {
                        call.reject(message);
                    }
                    emitErrorEvent(errorCode, message, currentSessionId);
                    finishSession(currentSessionId, "error", errorCode);
                }
            }
        );
    }

    private void triggerOnDeviceModelDownload(
        Intent intent,
        boolean partialResults,
        PluginCall call,
        long currentSessionId,
        boolean restarting
    ) {
        speechRecognizer.triggerModelDownload(
            intent,
            mainExecutor(),
            new ModelDownloadListener() {
                @Override
                public void onProgress(int completedPercent) {}

                @Override
                public void onSuccess() {
                    startInlineListening(intent, partialResults, call, currentSessionId, restarting);
                }

                @Override
                public void onScheduled() {
                    String message = "On-device speech model download was scheduled. Try again once it finishes.";
                    if (call != null) {
                        call.reject(message);
                    }
                    emitErrorEvent("MODEL_DOWNLOAD_SCHEDULED", message, currentSessionId);
                    finishSession(currentSessionId, "error", "MODEL_DOWNLOAD_SCHEDULED");
                }

                @Override
                public void onError(int error) {
                    String errorCode = getErrorCode(error);
                    String message = getErrorText(error);
                    if (call != null) {
                        call.reject(message);
                    }
                    emitErrorEvent(errorCode, message, currentSessionId);
                    finishSession(currentSessionId, "error", errorCode);
                }
            }
        );
    }

    private void startInlineListening(Intent intent, boolean partialResults, PluginCall call, long currentSessionId, boolean restarting) {
        final long muteGeneration;
        try {
            lock.lock();
            muteGeneration = recognizerGeneration;
            muteRecognizerBeepIfNeededLocked(muteGeneration);
        } finally {
            lock.unlock();
        }
        speechRecognizer.startListening(intent);
        handler.postDelayed(
            () -> {
                try {
                    lock.lock();
                    if (currentSessionId != sessionId) {
                        return;
                    }
                    restoreRecognizerBeepIfNeededLocked(muteGeneration);
                } finally {
                    lock.unlock();
                }
            },
            750
        );
        listening(true);
        state = ListeningState.STARTED;
        emitListeningState("started", currentSessionId, restarting ? "results" : "userStart", null, "started");
        if (partialResults && call != null) {
            activeStartCall = null;
            call.resolve();
        }
    }

    private void scheduleContinuousRestart(long currentSessionId) {
        handler.postDelayed(
            () -> {
                try {
                    lock.lock();
                    if (currentSessionId != sessionId || !continuousPTTMode || !pttButtonHeld || pendingStopReason != null) {
                        return;
                    }
                } finally {
                    lock.unlock();
                }

                beginListening(
                    lastLanguage,
                    lastMaxResults,
                    lastPrompt,
                    lastPartialResults,
                    false,
                    null,
                    lastAllowForSilence,
                    lastUseOnDeviceRecognition,
                    currentSessionId,
                    true
                );
            },
            CONTINUOUS_RESTART_DELAY_MS
        );
    }

    private void finishSession(long finishedSessionId, String explicitReason, String errorCode) {
        handler.post(() -> {
            PluginCall startCallToReject = null;
            boolean emitReady = true;
            String reason;

            try {
                lock.lock();
                if (finishedSessionId != sessionId) {
                    return;
                }

                reason = explicitReason != null ? explicitReason : (pendingStopReason != null ? pendingStopReason : "unknown");
                if (state != ListeningState.STOPPING) {
                    state = ListeningState.STOPPING;
                    emitListeningState("stoppingListening", finishedSessionId, reason, errorCode, null);
                }

                cancelPendingForceStopLocked();
                listening(false);
                restoreRecognizerBeepIfNeededLocked(mutedForGeneration);
                if (activeStartCall != null && !lastPartialResults && ("userStop".equals(reason) || "forceStop".equals(reason))) {
                    startCallToReject = activeStartCall;
                }
                activeStartCall = null;
                pendingStopReason = null;
                continuousPTTMode = false;
                popupSessionActive = false;
                popupSessionCancelled = false;
                forceStopped = false;
                resetPartialResultsCache();
                accumulatedResults = new StringBuilder();

                destroyCurrentRecognizerLocked();
                try {
                    recreateIdleRecognizerLocked();
                } catch (Exception ex) {
                    emitReady = false;
                    Logger.error(TAG, "Failed to recreate recognizer", ex);
                    emitErrorEvent("RECREATE_FAILED", ex.getMessage(), finishedSessionId);
                }

                state = ListeningState.IDLE;
                if (emitReady) {
                    notifyListeners(READY_FOR_NEXT_SESSION_EVENT, new JSObject().put("sessionId", finishedSessionId));
                }
                emitListeningState("stopped", finishedSessionId, reason, errorCode, "stopped");
            } finally {
                lock.unlock();
            }

            if (startCallToReject != null) {
                startCallToReject.reject("Recognition stopped before final results were produced.");
            }
        });
    }

    private void recreateIdleRecognizerLocked() {
        destroyCurrentRecognizerLocked();
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(bridge.getActivity());
        speechRecognizerUsesOnDevice = false;
        recognizerGeneration++;
        speechRecognizer.setRecognitionListener(new SpeechRecognitionListener(sessionId, recognizerGeneration));
    }

    private void destroyCurrentRecognizerLocked() {
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
    }

    private void cancelPendingForceStopLocked() {
        if (forceStopRunnable != null) {
            handler.removeCallbacks(forceStopRunnable);
            forceStopRunnable = null;
        }
    }

    private void scheduleFinishFallbackLocked(long currentSessionId, String reason, String errorCode, int delayMs) {
        handler.postDelayed(
            () -> {
                boolean shouldFinish;
                try {
                    lock.lock();
                    shouldFinish = currentSessionId == sessionId && state != ListeningState.IDLE;
                } finally {
                    lock.unlock();
                }

                if (shouldFinish) {
                    finishSession(currentSessionId, reason, errorCode);
                }
            },
            delayMs
        );
    }

    private JSObject buildForcedPartialResultLocked() {
        if (previousPartialResults.length() == 0 && accumulatedResults.length() == 0) {
            return null;
        }

        JSObject payload = new JSObject();
        payload.put("forced", true);
        if (previousPartialResults.length() > 0) {
            payload.put("matches", previousPartialResults);
        }
        String accumulatedText = buildCurrentTranscriptTextLocked();
        if (!accumulatedText.isEmpty()) {
            payload.put("accumulatedText", accumulatedText);
        }
        return payload;
    }

    private String buildCurrentTranscriptTextLocked() {
        String accumulatedText = accumulatedResults.toString().trim();
        String latestText = previousPartialResults.length() > 0 ? previousPartialResults.optString(0, "").trim() : "";

        if (accumulatedText.isEmpty()) {
            return latestText;
        }
        if (latestText.isEmpty()) {
            return accumulatedText;
        }
        return accumulatedText + " " + latestText;
    }

    private void listening(boolean value) {
        listening = value;
    }

    private void resetPartialResultsCache() {
        previousPartialResults = new JSONArray();
    }

    private boolean canUseOnDeviceRecognition() {
        return (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(bridge.getContext())
        );
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
        return (command) -> bridge.getActivity().runOnUiThread(command);
    }

    private void emitListeningState(String stateValue, long currentSessionId, String reason, String errorCode, String status) {
        JSObject payload = new JSObject();
        payload.put("state", stateValue);
        payload.put("sessionId", currentSessionId);
        payload.put("reason", reason);
        if (errorCode != null) {
            payload.put("errorCode", errorCode);
        }
        if (status != null) {
            payload.put("status", status);
        }
        notifyListeners(LISTENING_EVENT, payload);
    }

    private void emitErrorEvent(String errorCode, String message, long currentSessionId) {
        JSObject payload = new JSObject();
        payload.put("code", errorCode);
        payload.put("message", message);
        payload.put("sessionId", currentSessionId);
        notifyListeners(ERROR_EVENT, payload);
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

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        handler.removeCallbacksAndMessages(null);
        try {
            lock.lock();
            restoreRecognizerBeepIfNeededLocked(mutedForGeneration);
            destroyCurrentRecognizerLocked();
            activeStartCall = null;
            pendingStopReason = null;
            popupSessionActive = false;
            popupSessionCancelled = false;
            listening(false);
            state = ListeningState.IDLE;
        } finally {
            lock.unlock();
        }
    }

    private void muteRecognizerBeepIfNeededLocked(long generation) {
        if (!muteRecognizerBeep) {
            return;
        }

        AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }

        try {
            if (savedNotificationVolume == null) {
                savedNotificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
                savedSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM);
                mutedForGeneration = generation;
            }
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0);
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0);
        } catch (SecurityException ex) {
            Logger.warn(TAG, "Unable to mute recognizer beep: " + ex.getMessage());
        }
    }

    private void restoreRecognizerBeepIfNeededLocked(long generation) {
        if (savedNotificationVolume == null || generation != mutedForGeneration) {
            return;
        }

        AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotificationVolume, 0);
                if (savedSystemVolume != null) {
                    audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVolume, 0);
                }
            } catch (SecurityException ex) {
                Logger.warn(TAG, "Unable to restore recognizer beep volume: " + ex.getMessage());
            }
        }

        savedNotificationVolume = null;
        savedSystemVolume = null;
        mutedForGeneration = -1;
    }

    private class SpeechRecognitionListener implements RecognitionListener {

        private final long listenerSessionId;
        private final long listenerGeneration;
        private PluginCall call;
        private boolean partialResults;

        SpeechRecognitionListener(long listenerSessionId, long listenerGeneration) {
            this.listenerSessionId = listenerSessionId;
            this.listenerGeneration = listenerGeneration;
        }

        public void setCall(PluginCall call) {
            this.call = call;
        }

        public void setPartialResults(boolean partialResults) {
            this.partialResults = partialResults;
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
            if (isStale()) {
                return;
            }
            try {
                lock.lock();
                restoreRecognizerBeepIfNeededLocked(listenerGeneration);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void onBeginningOfSpeech() {}

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {}

        @Override
        public void onError(int error) {
            if (isStale()) {
                return;
            }

            String errorCode = getErrorCode(error);
            String errorMessage = getErrorText(error);
            emitErrorEvent(errorCode, errorMessage, listenerSessionId);

            boolean restartContinuous;
            try {
                lock.lock();
                restartContinuous =
                    continuousPTTMode &&
                    pttButtonHeld &&
                    pendingStopReason == null &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT);

                if (restartContinuous && previousPartialResults.length() > 0) {
                    String lastPartial = previousPartialResults.optString(0, "").trim();
                    if (!lastPartial.isEmpty()) {
                        if (accumulatedResults.length() > 0) {
                            accumulatedResults.append(" ");
                        }
                        accumulatedResults.append(lastPartial);
                    }
                    resetPartialResultsCache();
                    listening(false);
                }
            } finally {
                lock.unlock();
            }

            if (restartContinuous) {
                scheduleContinuousRestart(listenerSessionId);
                return;
            }

            if (call != null && !partialResults) {
                call.reject(errorMessage);
            }

            String reason = pendingStopReason != null
                ? pendingStopReason
                : (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "silence" : "error");
            finishSession(listenerSessionId, reason, errorCode);
        }

        @Override
        public void onResults(Bundle results) {
            if (isStale()) {
                return;
            }

            ArrayList<String> matches = buildMatchesWithUnstableText(results);
            if (matches == null) {
                matches = new ArrayList<>();
            }
            String resultText = matches.isEmpty() ? "" : matches.get(0);

            boolean restartContinuous;
            JSObject restartPayload = null;
            JSObject finalPayload = null;
            try {
                lock.lock();
                cancelPendingForceStopLocked();
                previousPartialResults = new JSONArray(matches);
                restartContinuous = continuousPTTMode && pttButtonHeld && pendingStopReason == null;

                if (restartContinuous) {
                    if (!resultText.trim().isEmpty()) {
                        if (accumulatedResults.length() > 0) {
                            accumulatedResults.append(" ");
                        }
                        accumulatedResults.append(resultText.trim());
                    }
                    restartPayload = new JSObject();
                    restartPayload.put("matches", new JSArray(matches));
                    restartPayload.put("accumulated", accumulatedResults.toString().trim());
                    restartPayload.put("isRestarting", true);
                    resetPartialResultsCache();
                    listening(false);
                } else if (partialResults) {
                    finalPayload = new JSObject();
                    finalPayload.put("matches", new JSArray(matches));
                    if (accumulatedResults.length() > 0) {
                        String accumulatedText = accumulatedResults.toString().trim();
                        if (!resultText.trim().isEmpty()) {
                            accumulatedText = accumulatedText + " " + resultText.trim();
                        }
                        finalPayload.put("accumulatedText", accumulatedText.trim());
                    }
                }
            } finally {
                lock.unlock();
            }

            if (restartContinuous) {
                if (restartPayload != null) {
                    notifyListeners(PARTIAL_RESULTS_EVENT, restartPayload);
                }
                scheduleContinuousRestart(listenerSessionId);
                return;
            }

            if (call != null && !partialResults) {
                call.resolve(new JSObject().put("status", "success").put("matches", new JSArray(matches)));
            } else if (finalPayload != null) {
                notifyListeners(PARTIAL_RESULTS_EVENT, finalPayload);
            }

            finishSession(listenerSessionId, pendingStopReason != null ? pendingStopReason : "results", null);
        }

        @Override
        public void onPartialResults(Bundle partialResultsBundle) {
            if (isStale() || forceStopped) {
                return;
            }

            ArrayList<String> matches = buildMatchesWithUnstableText(partialResultsBundle);
            if (matches == null || matches.isEmpty()) {
                return;
            }

            JSObject payload = null;
            try {
                lock.lock();
                JSArray matchesJSON = new JSArray(matches);
                JSONArray nextPartialResults = new JSONArray(matches);
                if (!previousPartialResults.toString().equals(nextPartialResults.toString())) {
                    previousPartialResults = nextPartialResults;
                    payload = new JSObject();
                    payload.put("matches", matchesJSON);
                    if (accumulatedResults.length() > 0) {
                        payload.put("accumulated", accumulatedResults.toString().trim());
                    }
                }
            } finally {
                lock.unlock();
            }

            if (payload != null) {
                notifyListeners(PARTIAL_RESULTS_EVENT, payload);
            }
        }

        @Override
        public void onSegmentResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches == null) {
                return;
            }
            notifyListeners(SEGMENT_RESULTS_EVENT, new JSObject().put("matches", new JSArray(matches)));
        }

        @Override
        public void onEndOfSegmentedSession() {
            notifyListeners(END_OF_SEGMENT_EVENT, new JSObject());
        }

        @Override
        public void onEvent(int eventType, Bundle params) {}

        private boolean isStale() {
            try {
                lock.lock();
                return listenerSessionId != sessionId || listenerGeneration != recognizerGeneration;
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
            if (unstableText == null) {
                return matches;
            }

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
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "SERVER_DISCONNECTED";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "SPEECH_TIMEOUT";
            default:
                return "UNKNOWN_" + errorCode;
        }
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
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "Server disconnected";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech input";
            default:
                return "Didn't understand, please try again. Error code: " + errorCode;
        }
    }
}
