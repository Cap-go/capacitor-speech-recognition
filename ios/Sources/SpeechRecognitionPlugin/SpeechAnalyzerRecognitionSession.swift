import Foundation
@preconcurrency import AVFoundation
import Speech

#if compiler(>=6.2)

@available(iOS 26.0, *)
enum SpeechAnalyzerRecognitionSupport {
    static func supports(locale: Locale) async -> Bool {
        guard SpeechTranscriber.isAvailable else {
            return false
        }

        let target = normalizedIdentifier(for: locale)
        let supportedLocales = await SpeechTranscriber.supportedLocales
        return supportedLocales.contains { normalizedIdentifier(for: $0) == target }
    }

    static func supportedLanguageIdentifiers() async -> [String] {
        let modernLocales = await SpeechTranscriber.supportedLocales
        return modernLocales
            .map(normalizedIdentifier(for:))
            .sorted()
    }

    static func normalizedIdentifier(for locale: Locale) -> String {
        locale.identifier(.bcp47)
    }
}

@available(iOS 26.0, *)
enum SpeechAnalyzerRecognitionError: LocalizedError {
    case unavailable
    case unsupportedLocale(String)
    case setupFailed(String)

    var errorDescription: String? {
        switch self {
        case .unavailable:
            return "Speech transcriber is not available on this device."
        case .unsupportedLocale(let language):
            return "Unsupported locale: \(language)"
        case .setupFailed(let message):
            return message
        }
    }
}

@available(iOS 26.0, *)
@MainActor
final class SpeechAnalyzerRecognitionSession {
    typealias ResultHandler = @MainActor ([String], Bool) -> Void
    typealias VoidHandler = @MainActor () -> Void
    typealias ErrorHandler = @MainActor (Error) -> Void

    private static let microphoneTapBufferSize: AVAudioFrameCount = 2048

    private let locale: Locale
    private let maxResults: Int
    private let includePartialResults: Bool
    private let processingActor = SpeechAnalyzerAudioProcessingActor()
    private let modelManager = SpeechAnalyzerModelManager()

    private lazy var audioEngine = AVAudioEngine()
    private var transcriber: SpeechTranscriber?
    private var analyzer: SpeechAnalyzer?
    private var analyzerInputContinuation: AsyncStream<AnalyzerInput>.Continuation?
    private var analyzerFormat: AVAudioFormat?
    private var resultTask: Task<Void, Never>?
    private var hasInstalledTap = false
    private var isTearingDown = false
    private var isAudioSessionActive = false

    var onListeningStarted: VoidHandler?
    var onListeningStopped: VoidHandler?
    var onResult: ResultHandler?
    var onError: ErrorHandler?

    var isRunning: Bool {
        audioEngine.isRunning || resultTask != nil || isTearingDown
    }

    init(locale: Locale, maxResults: Int, includePartialResults: Bool) {
        self.locale = locale
        self.maxResults = maxResults
        self.includePartialResults = includePartialResults
    }

    func start() async throws {
        guard SpeechTranscriber.isAvailable else {
            throw SpeechAnalyzerRecognitionError.unavailable
        }

        guard await SpeechAnalyzerRecognitionSupport.supports(locale: locale) else {
            throw SpeechAnalyzerRecognitionError.unsupportedLocale(
                SpeechAnalyzerRecognitionSupport.normalizedIdentifier(for: locale)
            )
        }

        let reportingOptions: Set<SpeechTranscriber.ReportingOption> = includePartialResults ? [.volatileResults] : []
        let transcriber = SpeechTranscriber(
            locale: locale,
            transcriptionOptions: [],
            reportingOptions: reportingOptions,
            attributeOptions: []
        )

        self.transcriber = transcriber
        try await modelManager.ensureModel(for: transcriber, locale: locale)

        let modules: [any SpeechModule] = [transcriber]
        guard let bestFormat = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: modules) else {
            throw SpeechAnalyzerRecognitionError.setupFailed("Unable to determine a compatible analyzer format.")
        }

        analyzerFormat = bestFormat

        let analyzer = SpeechAnalyzer(modules: modules)
        self.analyzer = analyzer

        let (inputSequence, inputContinuation) = AsyncStream<AnalyzerInput>.makeStream()
        analyzerInputContinuation = inputContinuation

        try await analyzer.start(inputSequence: inputSequence)
        startResultTask(for: transcriber)
        try configureAudioSession()
        try startAudioStreaming()
        onListeningStarted?()
    }

    func stop() async {
        await teardown(notifyStopped: true)
    }

    private func startResultTask(for transcriber: SpeechTranscriber) {
        resultTask = Task { [weak self] in
            guard let self else { return }

            do {
                for try await result in transcriber.results {
                    let transcript = String(result.text.characters).trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !transcript.isEmpty else {
                        continue
                    }

                    onResult?(Array([transcript].prefix(maxResults)), result.isFinal)
                }

                await teardown(notifyStopped: true)
            } catch is CancellationError {
                await teardown(notifyStopped: false)
            } catch {
                await teardown(notifyStopped: false)
                onError?(error)
            }
        }
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.duckOthers, .allowBluetoothHFP])
        try session.setActive(true, options: .notifyOthersOnDeactivation)
        isAudioSessionActive = true
    }

    private func startAudioStreaming() throws {
        let inputNode = audioEngine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)

        inputNode.removeTap(onBus: 0)
        inputNode.installTap(
            onBus: 0,
            bufferSize: Self.microphoneTapBufferSize,
            format: inputFormat
        ) { [weak self] buffer, _ in
            guard let self, let bufferCopy = buffer.copy() as? AVAudioPCMBuffer else {
                return
            }

            let sendableBuffer = SpeechAnalyzerSendablePCMBuffer(buffer: bufferCopy)
            Task {
                do {
                    try await self.processAudioBuffer(sendableBuffer)
                } catch {
                    self.onError?(error)
                    await self.teardown(notifyStopped: false)
                }
            }
        }

        hasInstalledTap = true
        audioEngine.prepare()
        try audioEngine.start()
    }

    private func processAudioBuffer(_ buffer: SpeechAnalyzerSendablePCMBuffer) async throws {
        guard let analyzerInputContinuation, let analyzerFormat else {
            throw SpeechAnalyzerRecognitionError.setupFailed("Analyzer input is not ready.")
        }

        let analyzerInput = try await processingActor.makeAnalyzerInput(
            from: buffer,
            analyzerFormat: analyzerFormat
        )
        analyzerInputContinuation.yield(analyzerInput)
    }

    private func teardown(notifyStopped: Bool) async {
        if isTearingDown {
            return
        }

        isTearingDown = true

        let currentTask = resultTask
        resultTask = nil
        currentTask?.cancel()

        if audioEngine.isRunning {
            audioEngine.stop()
        }

        if hasInstalledTap {
            audioEngine.inputNode.removeTap(onBus: 0)
            hasInstalledTap = false
        }

        analyzerInputContinuation?.finish()
        analyzerInputContinuation = nil

        do {
            try await analyzer?.finalizeAndFinishThroughEndOfInput()
        } catch {
            // Best-effort shutdown. The plugin still needs native cleanup to succeed.
        }

        analyzer = nil
        analyzerFormat = nil
        transcriber = nil

        await modelManager.releaseLocales()
        deactivateAudioSessionIfNeeded()

        isTearingDown = false

        if notifyStopped {
            onListeningStopped?()
        }
    }

    private func deactivateAudioSessionIfNeeded() {
        guard isAudioSessionActive else {
            return
        }

        do {
            try AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        } catch {
            // Ignore deactivation failures during cleanup.
        }

        isAudioSessionActive = false
    }
}

@available(iOS 26.0, *)
private actor SpeechAnalyzerModelManager {
    private var reservedLocales = Set<String>()

    func ensureModel(for transcriber: SpeechTranscriber, locale: Locale) async throws {
        guard await SpeechAnalyzerRecognitionSupport.supports(locale: locale) else {
            throw SpeechAnalyzerRecognitionError.unsupportedLocale(
                SpeechAnalyzerRecognitionSupport.normalizedIdentifier(for: locale)
            )
        }

        let installedLocales = await SpeechTranscriber.installedLocales
        let target = SpeechAnalyzerRecognitionSupport.normalizedIdentifier(for: locale)
        let hasInstalledModel = installedLocales.contains {
            SpeechAnalyzerRecognitionSupport.normalizedIdentifier(for: $0) == target
        }

        if !hasInstalledModel,
           let installer = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            try await installer.downloadAndInstall()
        }

        try await reserveLocaleIfNeeded(locale)
    }

    func releaseLocales() async {
        let allReservedLocales = await AssetInventory.reservedLocales
        for locale in allReservedLocales {
            let normalized = SpeechAnalyzerRecognitionSupport.normalizedIdentifier(for: locale)
            if reservedLocales.contains(normalized) {
                await AssetInventory.release(reservedLocale: locale)
                reservedLocales.remove(normalized)
            }
        }
    }

    private func reserveLocaleIfNeeded(_ locale: Locale) async throws {
        let target = SpeechAnalyzerRecognitionSupport.normalizedIdentifier(for: locale)
        let existingReservations = await AssetInventory.reservedLocales
        let hasReservation = existingReservations.contains {
            SpeechAnalyzerRecognitionSupport.normalizedIdentifier(for: $0) == target
        }

        if hasReservation {
            return
        }

        try await AssetInventory.reserve(locale: locale)
        reservedLocales.insert(target)
    }
}

@available(iOS 26.0, *)
private struct SpeechAnalyzerSendablePCMBuffer: @unchecked Sendable {
    let buffer: AVAudioPCMBuffer
}

@available(iOS 26.0, *)
private actor SpeechAnalyzerAudioProcessingActor {
    private let converter = SpeechAnalyzerBufferConverter()

    func makeAnalyzerInput(
        from buffer: SpeechAnalyzerSendablePCMBuffer,
        analyzerFormat: AVAudioFormat
    ) throws -> AnalyzerInput {
        let convertedBuffer = try converter.convertBuffer(buffer.buffer, to: analyzerFormat)
        return AnalyzerInput(buffer: convertedBuffer)
    }
}

@available(iOS 26.0, *)
private final class SpeechAnalyzerBufferConverter: @unchecked Sendable {
    private var converter: AVAudioConverter?

    func convertBuffer(_ buffer: AVAudioPCMBuffer, to format: AVAudioFormat) throws -> AVAudioPCMBuffer {
        let inputFormat = buffer.format
        guard inputFormat != format else {
            return buffer
        }

        if converter == nil || converter?.outputFormat != format {
            converter = AVAudioConverter(from: inputFormat, to: format)
            converter?.primeMethod = .none
        }

        guard let converter else {
            throw SpeechAnalyzerRecognitionError.setupFailed("Failed to create audio converter.")
        }

        let sampleRateRatio = converter.outputFormat.sampleRate / converter.inputFormat.sampleRate
        let scaledFrameLength = Double(buffer.frameLength) * sampleRateRatio
        let frameCapacity = AVAudioFrameCount(scaledFrameLength.rounded(.up))

        guard let conversionBuffer = AVAudioPCMBuffer(
            pcmFormat: converter.outputFormat,
            frameCapacity: frameCapacity
        ) else {
            throw SpeechAnalyzerRecognitionError.setupFailed("Failed to create conversion buffer.")
        }

        var conversionError: NSError?

        final class BufferState: @unchecked Sendable {
            var hasSuppliedBuffer = false
        }

        let bufferState = BufferState()
        let status = converter.convert(to: conversionBuffer, error: &conversionError) { _, statusPointer in
            defer {
                bufferState.hasSuppliedBuffer = true
            }

            statusPointer.pointee = bufferState.hasSuppliedBuffer ? .noDataNow : .haveData
            return bufferState.hasSuppliedBuffer ? nil : buffer
        }

        if status == .error {
            throw conversionError ?? SpeechAnalyzerRecognitionError.setupFailed("Audio conversion failed.")
        }

        return conversionBuffer
    }
}

#else

enum SpeechAnalyzerRecognitionSupport {
    static func supports(locale _: Locale) async -> Bool {
        false
    }

    static func supportedLanguageIdentifiers() async -> [String] {
        []
    }

    static func normalizedIdentifier(for locale: Locale) -> String {
        locale.identifier
    }
}

enum SpeechAnalyzerRecognitionError: LocalizedError {
    case unavailable

    var errorDescription: String? {
        "Speech analyzer requires a newer Apple SDK."
    }
}

@MainActor
final class SpeechAnalyzerRecognitionSession: NSObject {
    typealias ResultHandler = @MainActor ([String], Bool) -> Void
    typealias VoidHandler = @MainActor () -> Void
    typealias ErrorHandler = @MainActor (Error) -> Void

    var isRunning = false
    var onListeningStarted: VoidHandler?
    var onListeningStopped: VoidHandler?
    var onResult: ResultHandler?
    var onError: ErrorHandler?

    init(locale _: Locale, maxResults _: Int, includePartialResults _: Bool) {}

    func start() async throws {
        throw SpeechAnalyzerRecognitionError.unavailable
    }

    func stop() async {
        isRunning = false
    }
}

#endif
