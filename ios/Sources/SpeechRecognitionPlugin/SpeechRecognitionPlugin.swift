import AVFoundation
import Capacitor
import Foundation
import Speech

private enum PermissionState: String {
    case granted
    case denied
    case prompt
}

private enum ListeningReason: String {
    case userStart
    case userStop
    case forceStop
    case results
    case silence
    case error
    case unknown
}

// swiftlint:disable type_body_length
@objc(SpeechRecognitionPlugin)
public final class SpeechRecognitionPlugin: CAPPlugin, CAPBridgedPlugin {
    private let pluginVersion = "8.0.10"
    public let identifier = "SpeechRecognitionPlugin"
    public let jsName = "SpeechRecognition"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "available", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isOnDeviceRecognitionAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "forceStop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLastPartialResult", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPTTState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSupportedLanguages", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isListening", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPluginVersion", returnType: CAPPluginReturnPromise)
    ]

    private let audioEngine = AVAudioEngine()
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var speechRecognizer: SFSpeechRecognizer?
    private var modernRecognitionSession: AnyObject?
    private var activeCall: CAPPluginCall?
    private var currentOptions: RecognitionOptions?
    private var hasInstalledTap = false
    private var latestPartialMatches: [String] = []
    private var pttButtonHeld = false
    private var nextSessionId = 0
    private var activeSessionId: Int?
    private var pendingStopReason: ListeningReason?
    private var emittedStoppingSessionId: Int?
    private var accumulatedTranscript = ""
    private var pendingContinuousRestartSessionId: Int?
    private var continuousRestartWorkItem: DispatchWorkItem?

    private let maxDefaultResults = 5
    private let continuousRestartDelay: TimeInterval = 0.1

    @objc func available(_ call: CAPPluginCall) {
        let locale = Locale(identifier: call.getString("language") ?? Locale.current.identifier)
        let recognizer = SFSpeechRecognizer(locale: locale)
        call.resolve(["available": recognizer?.isAvailable ?? false])
    }

    @objc func isOnDeviceRecognitionAvailable(_ call: CAPPluginCall) {
        let locale = Locale(identifier: call.getString("language") ?? Locale.current.identifier)
        if #available(iOS 26.0, *) {
            Task { @MainActor in
                let isAvailable = await SpeechAnalyzerRecognitionSupport.supports(locale: locale)
                call.resolve(["available": isAvailable])
            }
            return
        }

        call.resolve(["available": false])
    }

    @objc func start(_ call: CAPPluginCall) {
        if activeSessionId != nil || isRecognitionActive {
            call.reject("Speech recognition is already running.")
            return
        }

        guard isSpeechPermissionGranted else {
            call.reject("Missing speech recognition permission.")
            return
        }

        let contextualStrings = (call.getArray("contextualStrings", String.self) ?? [])
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        let options = RecognitionOptions(
            language: call.getString("language") ?? Locale.current.identifier,
            maxResults: call.getInt("maxResults") ?? maxDefaultResults,
            partialResults: call.getBool("partialResults") ?? false,
            addPunctuation: call.getBool("addPunctuation") ?? false,
            contextualStrings: contextualStrings,
            useOnDeviceRecognition: call.getBool("useOnDeviceRecognition") ?? false,
            continuousPTT: call.getBool("continuousPTT") ?? false
        )

        if options.continuousPTT && !options.partialResults {
            call.reject("continuousPTT requires partialResults: true.")
            return
        }

        nextSessionId += 1
        let sessionId = nextSessionId
        activeSessionId = sessionId
        activeCall = call
        currentOptions = options
        latestPartialMatches = []
        accumulatedTranscript = ""
        pendingStopReason = nil
        emittedStoppingSessionId = nil
        cancelPendingContinuousRestart()

        emitListeningState(
            state: "startingListening",
            sessionId: sessionId,
            reason: .userStart
        )

        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            guard granted else {
                DispatchQueue.main.async {
                    guard self.activeSessionId == sessionId else {
                        return
                    }
                    self.emitErrorEvent(
                        code: "MICROPHONE_PERMISSION_DENIED",
                        message: "User denied microphone access.",
                        sessionId: sessionId
                    )
                    self.activeCall?.reject("User denied microphone access.")
                    self.activeCall = nil
                    self.finishSessionIfNeeded(sessionId: sessionId, reason: .error, errorCode: "MICROPHONE_PERMISSION_DENIED")
                }
                return
            }

            DispatchQueue.main.async {
                self.beginRecognition(call: call, options: options, sessionId: sessionId, restarting: false)
            }
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        guard let sessionId = activeSessionId else {
            call.resolve()
            return
        }

        pendingStopReason = .userStop
        emitStoppingIfNeeded(sessionId: sessionId, reason: .userStop, errorCode: nil)
        rejectPendingStartCallIfNeeded(message: "Recognition stopped before final results were produced.")

        if #available(iOS 26.0, *), let modernSession = modernRecognitionSession as? SpeechAnalyzerRecognitionSession {
            Task { @MainActor in
                await modernSession.stop()
                self.finishSessionIfNeeded(sessionId: sessionId, reason: .userStop, errorCode: nil, sessionAlreadyStopped: true)
                call.resolve()
            }
            return
        }

        clearLegacyRecognitionResources()
        finishSessionIfNeeded(sessionId: sessionId, reason: .userStop, errorCode: nil, sessionAlreadyStopped: true)
        call.resolve()
    }

    @objc func forceStop(_ call: CAPPluginCall) {
        guard let sessionId = activeSessionId else {
            call.resolve()
            return
        }

        pendingStopReason = .forceStop
        emitStoppingIfNeeded(sessionId: sessionId, reason: .forceStop, errorCode: nil)
        rejectPendingStartCallIfNeeded(message: "Recognition force stopped before final results were produced.")

        if !latestPartialMatches.isEmpty || !accumulatedTranscriptText().isEmpty {
            emitPartialResultEvent(matches: latestPartialMatches, forced: true, includeAccumulatedText: true)
        }

        if #available(iOS 26.0, *), let modernSession = modernRecognitionSession as? SpeechAnalyzerRecognitionSession {
            Task { @MainActor in
                await modernSession.stop()
                self.finishSessionIfNeeded(sessionId: sessionId, reason: .forceStop, errorCode: nil, sessionAlreadyStopped: true)
                call.resolve()
            }
            return
        }

        clearLegacyRecognitionResources()
        finishSessionIfNeeded(sessionId: sessionId, reason: .forceStop, errorCode: nil, sessionAlreadyStopped: true)
        call.resolve()
    }

    @objc func getLastPartialResult(_ call: CAPPluginCall) {
        let text = currentTranscriptText()
        call.resolve([
            "available": !text.isEmpty,
            "text": text,
            "matches": latestPartialMatches
        ])
    }

    @objc func setPTTState(_ call: CAPPluginCall) {
        let held = call.getBool("held") ?? false
        pttButtonHeld = held
        if held {
            latestPartialMatches = []
            accumulatedTranscript = ""
            pendingStopReason = nil
            cancelPendingContinuousRestart()
        }
        call.resolve()
    }

    @objc func isListening(_ call: CAPPluginCall) {
        call.resolve(["listening": isRecognitionActive])
    }

    @objc func getSupportedLanguages(_ call: CAPPluginCall) {
        let identifiers = SFSpeechRecognizer
            .supportedLocales()
            .map(\.identifier)
            .sorted()
        call.resolve(["languages": identifiers])
    }

    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        call.resolve(["speechRecognition": permissionState.rawValue])
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        SFSpeechRecognizer.requestAuthorization { status in
            switch status {
            case .authorized:
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    DispatchQueue.main.async {
                        let result: PermissionState = granted ? .granted : .denied
                        call.resolve(["speechRecognition": result.rawValue])
                    }
                }
            case .denied, .restricted:
                DispatchQueue.main.async {
                    call.resolve(["speechRecognition": PermissionState.denied.rawValue])
                }
            case .notDetermined:
                DispatchQueue.main.async {
                    call.resolve(["speechRecognition": PermissionState.prompt.rawValue])
                }
            @unknown default:
                DispatchQueue.main.async {
                    call.resolve(["speechRecognition": PermissionState.prompt.rawValue])
                }
            }
        }
    }

    @objc func getPluginVersion(_ call: CAPPluginCall) {
        call.resolve(["version": pluginVersion])
    }

    private func beginRecognition(call: CAPPluginCall?, options: RecognitionOptions, sessionId: Int, restarting: Bool) {
        Task { @MainActor in
            guard activeSessionId == sessionId else {
                return
            }

            let locale = Locale(identifier: options.language)
            if #available(iOS 26.0, *),
               options.useOnDeviceRecognition,
               await SpeechAnalyzerRecognitionSupport.supports(locale: locale) {
                beginModernRecognition(call: call, options: options, locale: locale, sessionId: sessionId, restarting: restarting)
            } else {
                beginLegacyRecognition(call: call, options: options, locale: locale, sessionId: sessionId, restarting: restarting)
            }
        }
    }

    private func beginLegacyRecognition(
        call: CAPPluginCall?,
        options: RecognitionOptions,
        locale: Locale,
        sessionId: Int,
        restarting: Bool
    ) {
        guard activeSessionId == sessionId else {
            return
        }

        guard let recognizer = SFSpeechRecognizer(locale: locale) else {
            call?.reject("Unsupported locale: \(options.language)")
            emitErrorEvent(code: "UNSUPPORTED_LOCALE", message: "Unsupported locale: \(options.language)", sessionId: sessionId)
            activeCall = nil
            finishSessionIfNeeded(sessionId: sessionId, reason: .error, errorCode: "UNSUPPORTED_LOCALE")
            return
        }

        guard recognizer.isAvailable else {
            call?.reject("Speech recognizer is currently unavailable.")
            emitErrorEvent(code: "RECOGNIZER_UNAVAILABLE", message: "Speech recognizer is currently unavailable.", sessionId: sessionId)
            activeCall = nil
            finishSessionIfNeeded(sessionId: sessionId, reason: .error, errorCode: "RECOGNIZER_UNAVAILABLE")
            return
        }

        speechRecognizer = recognizer

        do {
            try configureAudioSession()
        } catch {
            call?.reject("Failed to configure audio session: \(error.localizedDescription)")
            emitErrorEvent(code: "AUDIO_SESSION_FAILED", message: error.localizedDescription, sessionId: sessionId)
            activeCall = nil
            finishSessionIfNeeded(sessionId: sessionId, reason: .error, errorCode: "AUDIO_SESSION_FAILED")
            return
        }

        let recognitionRequest = SFSpeechAudioBufferRecognitionRequest()
        recognitionRequest.shouldReportPartialResults = options.partialResults
        if !options.contextualStrings.isEmpty {
            recognitionRequest.contextualStrings = options.contextualStrings
        }
        if #available(iOS 16.0, *) {
            recognitionRequest.addsPunctuation = options.addPunctuation
        }
        self.recognitionRequest = recognitionRequest

        let inputNode = audioEngine.inputNode
        let recordingFormat = inputNode.outputFormat(forBus: 0)
        inputNode.removeTap(onBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { [weak self] buffer, _ in
            self?.recognitionRequest?.append(buffer)
        }
        hasInstalledTap = true

        audioEngine.prepare()

        do {
            try audioEngine.start()
            emitListeningState(state: "started", sessionId: sessionId, reason: restarting ? .results : .userStart, status: "started")
        } catch {
            call?.reject("Unable to start audio engine: \(error.localizedDescription)")
            emitErrorEvent(code: "AUDIO_ENGINE_START_FAILED", message: error.localizedDescription, sessionId: sessionId)
            activeCall = nil
            finishSessionIfNeeded(sessionId: sessionId, reason: .error, errorCode: "AUDIO_ENGINE_START_FAILED")
            return
        }

        if options.partialResults, let call {
            call.resolve()
            activeCall = nil
        }

        recognitionTask = recognizer.recognitionTask(with: recognitionRequest) { [weak self] result, error in
            guard let self else {
                return
            }

            DispatchQueue.main.async {
                guard self.activeSessionId == sessionId else {
                    return
                }

                if let result {
                    let matches = self.buildMatches(from: result, maxResults: options.maxResults)
                    self.latestPartialMatches = matches

                    if result.isFinal {
                        if self.prepareContinuousRestartIfNeeded(matches: matches, sessionId: sessionId) {
                            self.clearLegacyRecognitionResources()
                            self.scheduleContinuousRestart(sessionId: sessionId)
                            return
                        }

                        if options.partialResults {
                            self.emitPartialResultEvent(
                                matches: matches,
                                includeAccumulatedText: !self.accumulatedTranscriptText().isEmpty
                            )
                        } else {
                            let startCall = self.activeCall
                            self.activeCall = nil
                            startCall?.resolve(["matches": matches])
                        }

                        self.finishSessionIfNeeded(
                            sessionId: sessionId,
                            reason: self.pendingStopReason ?? .results,
                            errorCode: nil
                        )
                    } else if options.partialResults {
                        self.emitPartialResultEvent(matches: matches)
                    }
                }

                if let error {
                    self.handleRecognitionError(error, sessionId: sessionId)
                }
            }
        }
    }

    @available(iOS 26.0, *)
    @MainActor
    private func beginModernRecognition(
        call: CAPPluginCall?,
        options: RecognitionOptions,
        locale: Locale,
        sessionId: Int,
        restarting: Bool
    ) {
        let session = SpeechAnalyzerRecognitionSession(
            locale: locale,
            maxResults: options.maxResults,
            includePartialResults: options.partialResults
        )
        modernRecognitionSession = session

        session.onListeningStarted = { [weak self, weak session] in
            guard let self, let session, self.modernRecognitionSession === session, self.activeSessionId == sessionId else {
                return
            }

            self.emitListeningState(state: "started", sessionId: sessionId, reason: restarting ? .results : .userStart, status: "started")
        }

        session.onListeningStopped = { [weak self, weak session] in
            guard let self, let session, self.modernRecognitionSession === session, self.activeSessionId == sessionId else {
                return
            }

            if self.pendingContinuousRestartSessionId == sessionId {
                self.modernRecognitionSession = nil
                self.scheduleContinuousRestart(sessionId: sessionId)
                return
            }

            self.finishSessionIfNeeded(
                sessionId: sessionId,
                reason: self.pendingStopReason ?? .results,
                errorCode: nil,
                sessionAlreadyStopped: true
            )
        }

        session.onResult = { [weak self, weak session] matches, isFinal in
            guard let self, let session, self.modernRecognitionSession === session, self.activeSessionId == sessionId else {
                return
            }

            self.latestPartialMatches = matches

            if isFinal {
                if self.prepareContinuousRestartIfNeeded(matches: matches, sessionId: sessionId) {
                    return
                }

                if options.partialResults {
                    self.emitPartialResultEvent(
                        matches: matches,
                        includeAccumulatedText: !self.accumulatedTranscriptText().isEmpty
                    )
                } else {
                    let startCall = self.activeCall
                    self.activeCall = nil
                    startCall?.resolve(["matches": matches])
                }
                self.pendingStopReason = self.pendingStopReason ?? .results
            } else if options.partialResults {
                self.emitPartialResultEvent(matches: matches)
            }
        }

        session.onError = { [weak self, weak session] error in
            guard let self, let session, self.modernRecognitionSession === session, self.activeSessionId == sessionId else {
                return
            }

            self.handleRecognitionError(error, sessionId: sessionId)
        }

        Task { @MainActor [weak self, weak session] in
            guard let self, let session, self.modernRecognitionSession === session, self.activeSessionId == sessionId else {
                return
            }

            do {
                try await session.start()
                if options.partialResults, let call {
                    call.resolve()
                    self.activeCall = nil
                }
            } catch {
                call?.reject(error.localizedDescription)
                self.emitErrorEvent(code: "MODERN_START_FAILED", message: error.localizedDescription, sessionId: sessionId)
                self.activeCall = nil
                self.finishSessionIfNeeded(sessionId: sessionId, reason: .error, errorCode: "MODERN_START_FAILED")
            }
        }
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, options: [.defaultToSpeaker, .duckOthers])
        try session.setMode(.measurement)
        try session.setActive(true, options: .notifyOthersOnDeactivation)
    }

    private func clearLegacyRecognitionResources() {
        if audioEngine.isRunning {
            audioEngine.stop()
        }

        if hasInstalledTap {
            audioEngine.inputNode.removeTap(onBus: 0)
            hasInstalledTap = false
        }

        recognitionRequest?.endAudio()
        recognitionRequest = nil
        recognitionTask?.cancel()
        recognitionTask = nil
        speechRecognizer = nil
    }

    private func finishSessionIfNeeded(
        sessionId: Int,
        reason: ListeningReason,
        errorCode: String?,
        sessionAlreadyStopped: Bool = false
    ) {
        guard activeSessionId == sessionId else {
            return
        }

        emitStoppingIfNeeded(sessionId: sessionId, reason: reason, errorCode: errorCode)

        activeSessionId = nil
        currentOptions = nil
        pendingStopReason = nil
        emittedStoppingSessionId = nil
        cancelPendingContinuousRestart()

        if #available(iOS 26.0, *),
           let modernSession = modernRecognitionSession as? SpeechAnalyzerRecognitionSession,
           !sessionAlreadyStopped {
            Task { @MainActor in
                await modernSession.stop()
                if self.modernRecognitionSession === modernSession {
                    self.modernRecognitionSession = nil
                }
                self.finalizeFinishedSession(sessionId: sessionId, reason: reason, errorCode: errorCode)
            }
            return
        }

        if #available(iOS 26.0, *),
           let modernSession = modernRecognitionSession as? SpeechAnalyzerRecognitionSession,
           sessionAlreadyStopped,
           modernRecognitionSession === modernSession {
            modernRecognitionSession = nil
        }

        finalizeFinishedSession(sessionId: sessionId, reason: reason, errorCode: errorCode)
    }

    private func rejectPendingStartCallIfNeeded(message: String) {
        guard let startCall = activeCall else {
            return
        }

        activeCall = nil
        startCall.reject(message)
    }

    private func handleRecognitionError(_ error: Error, sessionId: Int) {
        guard activeSessionId == sessionId else {
            return
        }

        if pendingContinuousRestartSessionId == sessionId, pendingStopReason == nil {
            return
        }

        let code = errorCode(for: error)
        emitErrorEvent(code: code, message: error.localizedDescription, sessionId: sessionId)

        if let startCall = activeCall, !(currentOptions?.partialResults ?? false) {
            activeCall = nil
            startCall.reject(error.localizedDescription)
        }

        finishSessionIfNeeded(sessionId: sessionId, reason: pendingStopReason ?? .error, errorCode: code)
    }

    private func buildMatches(from result: SFSpeechRecognitionResult, maxResults: Int) -> [String] {
        var matches: [String] = []
        for transcription in result.transcriptions where matches.count < maxResults {
            matches.append(transcription.formattedString)
        }
        return matches
    }

    private func finalizeFinishedSession(sessionId: Int, reason: ListeningReason, errorCode: String?) {
        clearLegacyRecognitionResources()
        resetContinuousPTTState()
        notifyListeners("readyForNextSession", data: ["sessionId": sessionId])
        emitListeningState(state: "stopped", sessionId: sessionId, reason: reason, errorCode: errorCode, status: "stopped")
    }

    private func prepareContinuousRestartIfNeeded(matches: [String], sessionId: Int) -> Bool {
        guard shouldRestartContinuousPTT(sessionId: sessionId) else {
            return false
        }

        if let text = matches.first?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
            appendToAccumulatedTranscript(text)
        }

        pendingContinuousRestartSessionId = sessionId
        emitPartialResultEvent(matches: matches, isRestarting: true)
        latestPartialMatches = []
        return true
    }

    private func shouldRestartContinuousPTT(sessionId: Int) -> Bool {
        activeSessionId == sessionId &&
            currentOptions?.continuousPTT == true &&
            pttButtonHeld &&
            pendingStopReason == nil
    }

    private func scheduleContinuousRestart(sessionId: Int) {
        guard activeSessionId == sessionId, let options = currentOptions else {
            pendingContinuousRestartSessionId = nil
            return
        }

        cancelPendingContinuousRestart()

        let workItem = DispatchWorkItem { [weak self] in
            guard let self else {
                return
            }
            guard self.pendingContinuousRestartSessionId == sessionId,
                  self.shouldRestartContinuousPTT(sessionId: sessionId)
            else {
                self.pendingContinuousRestartSessionId = nil
                return
            }

            self.pendingContinuousRestartSessionId = nil
            self.beginRecognition(call: nil, options: options, sessionId: sessionId, restarting: true)
        }

        continuousRestartWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + continuousRestartDelay, execute: workItem)
    }

    private func cancelPendingContinuousRestart() {
        continuousRestartWorkItem?.cancel()
        continuousRestartWorkItem = nil
    }

    private func resetContinuousPTTState() {
        cancelPendingContinuousRestart()
        pendingContinuousRestartSessionId = nil
        accumulatedTranscript = ""
        latestPartialMatches = []
    }

    private func emitPartialResultEvent(
        matches: [String],
        isRestarting: Bool = false,
        forced: Bool = false,
        includeAccumulatedText: Bool = false
    ) {
        var payload: [String: Any] = [:]

        if !matches.isEmpty {
            payload["matches"] = matches
        }

        if includeAccumulatedText {
            let text = currentTranscriptText(currentMatches: matches)
            if !text.isEmpty {
                payload["accumulatedText"] = text
            }
        } else {
            let accumulated = accumulatedTranscriptText()
            if !accumulated.isEmpty {
                payload["accumulated"] = accumulated
            }
        }

        if isRestarting {
            payload["isRestarting"] = true
        }

        if forced {
            payload["forced"] = true
        }

        guard !payload.isEmpty else {
            return
        }

        notifyListeners("partialResults", data: payload)
    }

    private func appendToAccumulatedTranscript(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return
        }

        if accumulatedTranscript.isEmpty {
            accumulatedTranscript = trimmed
        } else {
            accumulatedTranscript += " " + trimmed
        }
    }

    private func accumulatedTranscriptText() -> String {
        accumulatedTranscript.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func currentTranscriptText(currentMatches: [String]? = nil) -> String {
        let accumulated = accumulatedTranscriptText()
        let current = (currentMatches ?? latestPartialMatches)
            .first?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        if accumulated.isEmpty {
            return current
        }
        if current.isEmpty {
            return accumulated
        }
        return accumulated + " " + current
    }

    private func emitListeningState(
        state: String,
        sessionId: Int,
        reason: ListeningReason,
        errorCode: String? = nil,
        status: String? = nil
    ) {
        var payload: [String: Any] = [
            "state": state,
            "sessionId": sessionId,
            "reason": reason.rawValue
        ]
        if let errorCode {
            payload["errorCode"] = errorCode
        }
        if let status {
            payload["status"] = status
        }
        notifyListeners("listeningState", data: payload)
    }

    private func emitStoppingIfNeeded(sessionId: Int, reason: ListeningReason, errorCode: String?) {
        guard emittedStoppingSessionId != sessionId else {
            return
        }

        emittedStoppingSessionId = sessionId
        emitListeningState(
            state: "stoppingListening",
            sessionId: sessionId,
            reason: reason,
            errorCode: errorCode
        )
    }

    private func emitErrorEvent(code: String, message: String, sessionId: Int) {
        notifyListeners("error", data: [
            "code": code,
            "message": message,
            "sessionId": sessionId
        ])
    }

    private func errorCode(for error: Error) -> String {
        let nsError = error as NSError
        let sanitizedDomain = nsError.domain
            .replacingOccurrences(of: "[^A-Za-z0-9]+", with: "_", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
            .uppercased()
        return sanitizedDomain.isEmpty ? "IOS_\(nsError.code)" : "\(sanitizedDomain)_\(nsError.code)"
    }

    private var isSpeechPermissionGranted: Bool {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized:
            return true
        case .notDetermined, .denied, .restricted:
            return false
        @unknown default:
            return false
        }
    }

    private var isRecognitionActive: Bool {
        if activeSessionId != nil {
            return true
        }

        if audioEngine.isRunning || recognitionTask != nil {
            return true
        }

        if #available(iOS 26.0, *), modernRecognitionSession != nil {
            return true
        }

        return false
    }

    private var permissionState: PermissionState {
        let speechStatus = SFSpeechRecognizer.authorizationStatus()
        let micStatus = AVAudioSession.sharedInstance().recordPermission

        if speechStatus == .denied || speechStatus == .restricted || micStatus == .denied {
            return .denied
        }

        if speechStatus == .notDetermined || micStatus == .undetermined {
            return .prompt
        }

        return .granted
    }
}
// swiftlint:enable type_body_length

private struct RecognitionOptions {
    let language: String
    let maxResults: Int
    let partialResults: Bool
    let addPunctuation: Bool
    let contextualStrings: [String]
    let useOnDeviceRecognition: Bool
    let continuousPTT: Bool
}
