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
    public let identifier = "SpeechRecognitionPlugin"
    public let jsName = "SpeechRecognition"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "available", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSupportedLanguages", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isListening", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise)
    ]

    private let audioEngine = AVAudioEngine()
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var speechRecognizer: SFSpeechRecognizer?
    private var activeCall: CAPPluginCall?
    private var currentOptions: RecognitionOptions?
    private var hasInstalledTap = false

    private let maxDefaultResults = 5

    @objc func available(_ call: CAPPluginCall) {
        let locale = Locale(identifier: call.getString("language") ?? Locale.current.identifier)
        let recognizer = SFSpeechRecognizer(locale: locale)
        call.resolve(["available": recognizer?.isAvailable ?? false])
    }

    @objc func start(_ call: CAPPluginCall) {
        if self.audioEngine.isRunning || recognitionTask != nil {
            call.reject("Speech recognition is already running.")
            return
        }

        guard isSpeechPermissionGranted else {
            call.reject("Missing speech recognition permission.")
            return
        }

        let options = RecognitionOptions(
            language: call.getString("language") ?? Locale.current.identifier,
            maxResults: call.getInt("maxResults") ?? maxDefaultResults,
            partialResults: call.getBool("partialResults") ?? false,
            addPunctuation: call.getBool("addPunctuation") ?? false
        )

        self.activeCall = call
        self.currentOptions = options

        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            guard granted else {
                DispatchQueue.main.async {
                    call.reject("User denied microphone access.")
                    self.cleanupRecognition(notifyStop: false)
                }
                return
            }

            DispatchQueue.main.async {
                self.beginRecognition(call: call, options: options)
            }
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        cleanupRecognition(notifyStop: true)
        call.resolve()
    }

    @objc func isListening(_ call: CAPPluginCall) {
        call.resolve(["listening": audioEngine.isRunning])
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
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: options.language)) else {
            call.reject("Unsupported locale: \(options.language)")
            cleanupRecognition(notifyStop: false)
            return
        }

        guard recognizer.isAvailable else {
            call.reject("Speech recognizer is currently unavailable.")
            cleanupRecognition(notifyStop: false)
            return
        }

        speechRecognizer = recognizer

        do {
            try configureAudioSession()
        } catch {
            call.reject("Failed to configure audio session: \(error.localizedDescription)")
            cleanupRecognition(notifyStop: false)
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
            cleanupRecognition(notifyStop: false)
            return
        }

        if options.partialResults {
            call.resolve()
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
                        self.activeCall?.resolve(["matches": matches])
                    }
                }

                if result.isFinal {
                    self.cleanupRecognition(notifyStop: true)
                }
            }

            if let error {
                self.handleRecognitionError(error)
            }
        }
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, options: [.defaultToSpeaker, .duckOthers])
        try session.setMode(.measurement)
        try session.setActive(true, options: .notifyOthersOnDeactivation)
    }

    private func cleanupRecognition(notifyStop: Bool) {
        DispatchQueue.main.async {
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

    private func handleRecognitionError(_ error: Error) {
        DispatchQueue.main.async {
            self.cleanupRecognition(notifyStop: true)
            self.activeCall?.reject(error.localizedDescription)
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

private struct RecognitionOptions {
    let language: String
    let maxResults: Int
    let partialResults: Bool
    let addPunctuation: Bool
}
