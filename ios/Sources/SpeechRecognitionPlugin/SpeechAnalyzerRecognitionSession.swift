import Foundation
@preconcurrency import AVFoundation
import Speech

#if compiler(>=6.2)

/// Which Apple transcriber module the SpeechAnalyzer path runs.
@available(iOS 26.0, *)
enum SpeechAnalyzerTranscriberKind: String {
    /// `SpeechTranscriber` (default).
    case speech
    /// `DictationTranscriber`, the model behind keyboard dictation.
    case dictation

    var isAvailable: Bool {
        switch self {
        case .speech: return SpeechTranscriber.isAvailable
        case .dictation: return true
        }
    }

    var supportedLocales: [Locale] {
        get async {
            switch self {
            case .speech: return await SpeechTranscriber.supportedLocales
            case .dictation: return await DictationTranscriber.supportedLocales
            }
        }
    }

    var installedLocales: [Locale] {
        get async {
            switch self {
            case .speech: return await SpeechTranscriber.installedLocales
            case .dictation: return await DictationTranscriber.installedLocales
            }
        }
    }
}

/// A transcriber module plus a uniform stream of `(text, isFinal)` results.
@available(iOS 26.0, *)
private enum SpeechAnalyzerTranscriber {
    case speech(SpeechTranscriber)
    case dictation(DictationTranscriber)

    init(kind: SpeechAnalyzerTranscriberKind, locale: Locale, includePartialResults: Bool) {
        switch kind {
        case .speech:
            let reportingOptions: Set<SpeechTranscriber.ReportingOption> = includePartialResults ? [.volatileResults] : []
            self = .speech(SpeechTranscriber(
                locale: locale,
                transcriptionOptions: [],
                reportingOptions: reportingOptions,
                attributeOptions: []
            ))
        case .dictation:
            self = .dictation(DictationTranscriber(locale: locale, preset: .progressiveShortDictation))
        }
    }

    var module: any SpeechModule {
        switch self {
        case .speech(let transcriber): return transcriber
        case .dictation(let transcriber): return transcriber
        }
    }

    var results: AsyncThrowingStream<(text: String, isFinal: Bool), Error> {
        switch self {
        case .speech(let transcriber):
            return Self.stream(transcriber.results) { (String($0.text.characters), $0.isFinal) }
        case .dictation(let transcriber):
            return Self.stream(transcriber.results) { (String($0.text.characters), $0.isFinal) }
        }
    }

    /// One adapter for both result types, so cancellation and error forwarding stay identical.
    private static func stream<Results: AsyncSequence & Sendable>(
        _ results: Results,
        _ transform: @escaping @Sendable (Results.Element) -> (text: String, isFinal: Bool)
    ) -> AsyncThrowingStream<(text: String, isFinal: Bool), Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for try await result in results {
                        continuation.yield(transform(result))
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

@available(iOS 26.0, *)
enum SpeechAnalyzerRecognitionSupport {
    static func supports(locale: Locale, kind: SpeechAnalyzerTranscriberKind = .speech) async -> Bool {
        guard kind.isAvailable else {
            return false
        }

        let target = normalizedIdentifier(for: locale)
        let supportedLocales = await kind.supportedLocales
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
    typealias AudioLevelHandler = @MainActor (Float) -> Void

    private static let microphoneTapBufferSize: AVAudioFrameCount = 2048

    private let locale: Locale
    private let maxResults: Int
    private let includePartialResults: Bool
    private let transcriberKind: SpeechAnalyzerTranscriberKind
    private let contextualStrings: [String]
    private let processingActor = SpeechAnalyzerAudioProcessingActor()
    private let modelManager = SpeechAnalyzerModelManager()

    private lazy var audioEngine = AVAudioEngine()
    private var transcriber: SpeechAnalyzerTranscriber?
    private var analyzer: SpeechAnalyzer?
    private var analyzerInputContinuation: AsyncStream<AnalyzerInput>.Continuation?
    private var analyzerFormat: AVAudioFormat?
    private var resultTask: Task<Void, Never>?
    private let inFlightAudioBuffers = InFlightAudioBufferCounter()
    private var hasInstalledTap = false
    private var isTearingDown = false
    private var sharedTeardownTask: Task<Void, Never>?
    private var isAudioSessionActive = false

    var onListeningStarted: VoidHandler?
    var onListeningStopped: VoidHandler?
    var onResult: ResultHandler?
    var onError: ErrorHandler?
    var onAudioLevel: AudioLevelHandler?
    /// Touched from the audio tap thread for emit throttling; only this session writes it.
    nonisolated(unsafe) private var lastAudioLevelEmitTime: CFTimeInterval = 0

    var isRunning: Bool {
        audioEngine.isRunning || resultTask != nil || isTearingDown
    }

    init(
        locale: Locale,
        maxResults: Int,
        includePartialResults: Bool,
        contextualStrings: [String] = [],
        transcriberKind: SpeechAnalyzerTranscriberKind = .speech
    ) {
        self.locale = locale
        self.maxResults = maxResults
        self.includePartialResults = includePartialResults
        self.transcriberKind = transcriberKind
        self.contextualStrings = contextualStrings
    }

    func start() async throws {
        guard transcriberKind.isAvailable else {
            throw SpeechAnalyzerRecognitionError.unavailable
        }

        guard await SpeechAnalyzerRecognitionSupport.supports(locale: locale, kind: transcriberKind) else {
            throw SpeechAnalyzerRecognitionError.unsupportedLocale(
                SpeechAnalyzerRecognitionSupport.normalizedIdentifier(for: locale)
            )
        }

        let transcriber = SpeechAnalyzerTranscriber(
            kind: transcriberKind,
            locale: locale,
            includePartialResults: includePartialResults
        )

        self.transcriber = transcriber
        try await modelManager.ensureModel(for: transcriber.module, kind: transcriberKind, locale: locale)

        let modules: [any SpeechModule] = [transcriber.module]
        guard let bestFormat = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: modules) else {
            throw SpeechAnalyzerRecognitionError.setupFailed("Unable to determine a compatible analyzer format.")
        }

        analyzerFormat = bestFormat

        let analyzer = SpeechAnalyzer(modules: modules)
        self.analyzer = analyzer

        if !contextualStrings.isEmpty {
            let analysisContext = AnalysisContext()
            analysisContext.contextualStrings[.general] = contextualStrings
            try await analyzer.setContext(analysisContext)
        }

        let (inputSequence, inputContinuation) = AsyncStream<AnalyzerInput>.makeStream()
        analyzerInputContinuation = inputContinuation

        try await analyzer.start(inputSequence: inputSequence)
        startResultTask(for: transcriber)
        try configureAudioSession()
        try startAudioStreaming()
        onListeningStarted?()
    }

    func stop() async {
        await requestTeardown(notifyStopped: true, callerIsResultTask: false)
    }

    private func requestTeardown(notifyStopped: Bool, callerIsResultTask: Bool) async {
        if let sharedTeardownTask {
            if !callerIsResultTask {
                await sharedTeardownTask.value
            }
            return
        }

        guard audioEngine.isRunning || resultTask != nil || hasInstalledTap || isTearingDown else {
            return
        }

        let task = Task { @MainActor in
            await self.performTeardown(notifyStopped: notifyStopped, callerIsResultTask: callerIsResultTask)
        }
        sharedTeardownTask = task
        await task.value
        sharedTeardownTask = nil
    }

    private func startResultTask(for transcriber: SpeechAnalyzerTranscriber) {
        resultTask = Task { [weak self] in
            guard let self else { return }

            do {
                for try await result in transcriber.results {
                    let transcript = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !transcript.isEmpty else {
                        continue
                    }

                    // The dictation preset always reports volatile results; honour partialResults: false.
                    guard result.isFinal || includePartialResults else {
                        continue
                    }

                    onResult?(Array([transcript].prefix(maxResults)), result.isFinal)
                }

                await requestTeardown(notifyStopped: true, callerIsResultTask: true)
            } catch is CancellationError {
                await requestTeardown(notifyStopped: false, callerIsResultTask: true)
            } catch {
                await requestTeardown(notifyStopped: false, callerIsResultTask: true)
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

            // Throttle on the tap thread first so we skip RMS/log and MainActor
            // hops when emitting faster than ~15 Hz.
            let now = CACurrentMediaTime()
            if AudioLevelMetering.shouldEmit(now: now, lastEmit: &self.lastAudioLevelEmitTime) {
                let level = AudioLevelMetering.normalizedAudioLevel(from: bufferCopy)
                Task { @MainActor [weak self] in
                    self?.onAudioLevel?(level)
                }
            }

            let sendableBuffer = SpeechAnalyzerSendablePCMBuffer(buffer: bufferCopy)
            self.inFlightAudioBuffers.registerPendingWork()
            Task { @MainActor in
                var processingError: Error?
                await self.inFlightAudioBuffers.track {
                    do {
                        try await self.processAudioBuffer(sendableBuffer)
                    } catch {
                        processingError = error
                    }
                }

                if let processingError {
                    self.onError?(processingError)
                    await self.requestTeardown(notifyStopped: false, callerIsResultTask: false)
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

        guard let continuation = self.analyzerInputContinuation else {
            return
        }

        continuation.yield(analyzerInput)
    }

    private func performTeardown(notifyStopped: Bool, callerIsResultTask: Bool) async {
        if isTearingDown {
            return
        }

        isTearingDown = true

        if audioEngine.isRunning {
            audioEngine.stop()
        }

        if hasInstalledTap {
            audioEngine.inputNode.removeTap(onBus: 0)
            hasInstalledTap = false
        }

        await inFlightAudioBuffers.waitUntilIdle()

        analyzerInputContinuation?.finish()
        analyzerInputContinuation = nil

        var finalizedCleanly = true
        do {
            try await analyzer?.finalizeAndFinishThroughEndOfInput()
        } catch {
            // Best-effort shutdown. The plugin still needs native cleanup to succeed.
            finalizedCleanly = false
            await analyzer?.cancelAndFinishNow()
        }

        if let drainingTask = resultTask {
            resultTask = nil
            if !finalizedCleanly {
                drainingTask.cancel()
            }
            if !callerIsResultTask {
                _ = await drainingTask.value
            }
        } else {
            resultTask = nil
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

    func ensureModel(for module: any SpeechModule, kind: SpeechAnalyzerTranscriberKind, locale: Locale) async throws {
        guard await SpeechAnalyzerRecognitionSupport.supports(locale: locale, kind: kind) else {
            throw SpeechAnalyzerRecognitionError.unsupportedLocale(
                SpeechAnalyzerRecognitionSupport.normalizedIdentifier(for: locale)
            )
        }

        let installedLocales = await kind.installedLocales
        let target = SpeechAnalyzerRecognitionSupport.normalizedIdentifier(for: locale)
        let hasInstalledModel = installedLocales.contains {
            SpeechAnalyzerRecognitionSupport.normalizedIdentifier(for: $0) == target
        }

        if !hasInstalledModel,
           let installer = try await AssetInventory.assetInstallationRequest(supporting: [module]) {
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
    typealias AudioLevelHandler = @MainActor (Float) -> Void

    var isRunning = false
    var onListeningStarted: VoidHandler?
    var onListeningStopped: VoidHandler?
    var onResult: ResultHandler?
    var onError: ErrorHandler?
    var onAudioLevel: AudioLevelHandler?

    init(locale _: Locale, maxResults _: Int, includePartialResults _: Bool, contextualStrings _: [String] = []) {}

    func start() async throws {
        throw SpeechAnalyzerRecognitionError.unavailable
    }

    func stop() async {
        isRunning = false
    }
}

#endif
