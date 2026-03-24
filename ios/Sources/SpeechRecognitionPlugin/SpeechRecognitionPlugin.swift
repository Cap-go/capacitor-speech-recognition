import AVFoundation
import Capacitor
import Foundation
import Speech

private enum PermissionState: String {
    case granted
    case denied
    case prompt
}

@objc(SpeechRecognitionPlugin)
public final class SpeechRecognitionPlugin: CAPPlugin, CAPBridgedPlugin {
    private let pluginVersion: String = "8.0.10"
    public let identifier = "SpeechRecognitionPlugin"
    public let jsName = "SpeechRecognition"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "available", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isOnDeviceRecognitionAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
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

    private let maxDefaultResults = 5

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
        if isRecognitionActive {
            CAPLog.print("[SpeechRecognition] Attempted to start while already running")
            call.reject("Speech recognition is already running.")
            return
        }

        guard isSpeechPermissionGranted else {
            CAPLog.print("[SpeechRecognition] Missing speech permission, rejecting start()")
            call.reject("Missing speech recognition permission.")
            return
        }

        let options = RecognitionOptions(
            language: call.getString("language") ?? Locale.current.identifier,
            maxResults: call.getInt("maxResults") ?? maxDefaultResults,
            partialResults: call.getBool("partialResults") ?? false,
            addPunctuation: call.getBool("addPunctuation") ?? false,
            useOnDeviceRecognition: call.getBool("useOnDeviceRecognition") ?? false
        )

        self.activeCall = call
        self.currentOptions = options
        CAPLog.print("[SpeechRecognition] Starting session | language=\(options.language) partialResults=\(options.partialResults) punctuation=\(options.addPunctuation)")

        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            guard granted else {
                CAPLog.print("[SpeechRecognition] Microphone permission denied by user")
                DispatchQueue.main.async {
                    call.reject("User denied microphone access.")
                    self.cleanupLegacyRecognition(notifyStop: false)
                }
                return
            }

            DispatchQueue.main.async {
                self.beginRecognition(call: call, options: options)
            }
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        CAPLog.print("[SpeechRecognition] stop() invoked")
        if #available(iOS 26.0, *), let modernSession = modernRecognitionSession as? SpeechAnalyzerRecognitionSession {
            Task { @MainActor in
                await modernSession.stop()
                call.resolve()
            }
            return
        }

        cleanupLegacyRecognition(notifyStop: true)
        call.resolve()
    }

    @objc func isListening(_ call: CAPPluginCall) {
        call.resolve(["listening": isRecognitionActive])
    }

    @objc func getSupportedLanguages(_ call: CAPPluginCall) {
        let identifiers = SFSpeechRecognizer
            .supportedLocales()
            .map { $0.identifier }
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

    private func beginRecognition(call: CAPPluginCall, options: RecognitionOptions) {
        Task { @MainActor in
            let locale = Locale(identifier: options.language)
            if #available(iOS 26.0, *),
               options.useOnDeviceRecognition,
               await SpeechAnalyzerRecognitionSupport.supports(locale: locale) {
                self.beginModernRecognition(call: call, options: options, locale: locale)
            } else {
                self.beginLegacyRecognition(call: call, options: options, locale: locale)
            }
        }
    }

    private func beginLegacyRecognition(call: CAPPluginCall, options: RecognitionOptions, locale: Locale) {
        guard let recognizer = SFSpeechRecognizer(locale: locale) else {
            call.reject("Unsupported locale: \(options.language)")
            cleanupLegacyRecognition(notifyStop: false)
            return
        }

        guard recognizer.isAvailable else {
            call.reject("Speech recognizer is currently unavailable.")
            cleanupLegacyRecognition(notifyStop: false)
            return
        }

        speechRecognizer = recognizer

        do {
            try configureAudioSession()
        } catch {
            call.reject("Failed to configure audio session: \(error.localizedDescription)")
            cleanupLegacyRecognition(notifyStop: false)
            return
        }

        let recognitionRequest = SFSpeechAudioBufferRecognitionRequest()
        recognitionRequest.shouldReportPartialResults = options.partialResults
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
            notifyListeners("listeningState", data: ["status": "started"])
        } catch {
            call.reject("Unable to start audio engine: \(error.localizedDescription)")
            cleanupLegacyRecognition(notifyStop: false)
            return
        }

        if options.partialResults {
            call.resolve()
            activeCall = nil
        }

        recognitionTask = recognizer.recognitionTask(with: recognitionRequest) { [weak self] result, error in
            guard let self else { return }
            if let result {
                let matches = self.buildMatches(from: result, maxResults: options.maxResults)
                if options.partialResults {
                    DispatchQueue.main.async {
                        self.notifyListeners("partialResults", data: ["matches": matches])
                    }
                } else if result.isFinal {
                    DispatchQueue.main.async {
                        let activeCall = self.activeCall
                        self.cleanupLegacyRecognition(notifyStop: true)
                        activeCall?.resolve(["matches": matches])
                    }
                    return
                }

                if result.isFinal {
                    self.cleanupLegacyRecognition(notifyStop: true)
                }
            }

            if let error {
                self.handleRecognitionError(error)
            }
        }
    }

    @available(iOS 26.0, *)
    @MainActor
    private func beginModernRecognition(call: CAPPluginCall, options: RecognitionOptions, locale: Locale) {
        let session = SpeechAnalyzerRecognitionSession(
            locale: locale,
            maxResults: options.maxResults,
            includePartialResults: options.partialResults
        )
        modernRecognitionSession = session

        session.onListeningStarted = { [weak self, weak session] in
            guard let self, let session, self.modernRecognitionSession === session else { return }
            self.notifyListeners("listeningState", data: ["status": "started"])
        }

        session.onListeningStopped = { [weak self, weak session] in
            guard let self, let session, self.modernRecognitionSession === session else { return }
            self.clearModernRecognition(notifyStop: true)
        }

        session.onResult = { [weak self, weak session] matches, isFinal in
            guard let self, let session, self.modernRecognitionSession === session else { return }

            if options.partialResults {
                self.notifyListeners("partialResults", data: ["matches": matches])
                return
            }

            guard isFinal else {
                return
            }

            let activeCall = self.activeCall
            self.clearModernRecognition(notifyStop: true)
            activeCall?.resolve(["matches": matches])
            Task { @MainActor in
                await session.stop()
            }
        }

        session.onError = { [weak self, weak session] error in
            guard let self, let session, self.modernRecognitionSession === session else { return }
            self.handleRecognitionError(error)
        }

        Task { @MainActor [weak self, weak session] in
            guard let self, let session, self.modernRecognitionSession === session else { return }
            do {
                try await session.start()
                if options.partialResults {
                    call.resolve()
                    self.activeCall = nil
                }
            } catch {
                call.reject(error.localizedDescription)
                self.clearModernRecognition(notifyStop: false)
            }
        }
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, options: [.defaultToSpeaker, .duckOthers])
        try session.setMode(.measurement)
        try session.setActive(true, options: .notifyOthersOnDeactivation)
    }

    private func cleanupLegacyRecognition(notifyStop: Bool) {
        DispatchQueue.main.async {
            CAPLog.print("[SpeechRecognition] Cleaning up recognition resources")
            if self.audioEngine.isRunning {
                self.audioEngine.stop()
            }

            if self.hasInstalledTap {
                self.audioEngine.inputNode.removeTap(onBus: 0)
                self.hasInstalledTap = false
            }

            self.recognitionRequest?.endAudio()
            self.recognitionRequest = nil
            self.recognitionTask?.cancel()
            self.recognitionTask = nil
            self.speechRecognizer = nil
            self.currentOptions = nil
            self.activeCall = nil

            if notifyStop {
                self.notifyListeners("listeningState", data: ["status": "stopped"])
            }
        }
    }

    private func clearModernRecognition(notifyStop: Bool) {
        DispatchQueue.main.async {
            CAPLog.print("[SpeechRecognition] Clearing iOS 26 recognition state")
            self.modernRecognitionSession = nil
            self.currentOptions = nil
            self.activeCall = nil

            if notifyStop {
                self.notifyListeners("listeningState", data: ["status": "stopped"])
            }
        }
    }

    private func handleRecognitionError(_ error: Error) {
        DispatchQueue.main.async {
            CAPLog.print("[SpeechRecognition] Error from recognizer: \(error.localizedDescription)")
            let activeCall = self.activeCall
            if self.modernRecognitionSession != nil {
                self.clearModernRecognition(notifyStop: true)
            } else {
                self.cleanupLegacyRecognition(notifyStop: true)
            }
            activeCall?.reject(error.localizedDescription)
        }
    }

    private func buildMatches(from result: SFSpeechRecognitionResult, maxResults: Int) -> [String] {
        var matches: [String] = []
        for transcription in result.transcriptions where matches.count < maxResults {
            matches.append(transcription.formattedString)
        }
        return matches
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
        if audioEngine.isRunning || recognitionTask != nil {
            return true
        }

        if #available(iOS 26.0, *), modernRecognitionSession is SpeechAnalyzerRecognitionSession {
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

    @objc func getPluginVersion(_ call: CAPPluginCall) {
        call.resolve(["version": pluginVersion])
    }
}

private struct RecognitionOptions {
    let language: String
    let maxResults: Int
    let partialResults: Bool
    let addPunctuation: Bool
    let useOnDeviceRecognition: Bool
}
