import XCTest
@testable import SpeechRecognitionPlugin

private final class AsyncTestFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    func set() {
        lock.lock()
        value = true
        lock.unlock()
    }

    func get() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

private final class AsyncTestGate: @unchecked Sendable {
    private let lock = NSLock()
    private var released = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func awaitRelease() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lock.lock()
            if released {
                lock.unlock()
                continuation.resume()
            } else {
                waiters.append(continuation)
                lock.unlock()
            }
        }
    }

    func release() {
        lock.lock()
        released = true
        let pending = waiters
        waiters = []
        lock.unlock()
        for waiter in pending {
            waiter.resume()
        }
    }
}

private final class IdleWaiterRegistrationAwaiter: @unchecked Sendable {
    private let lock = NSLock()
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var pendingSignals = 0

    func waitForNext() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lock.lock()
            if pendingSignals > 0 {
                pendingSignals -= 1
                lock.unlock()
                continuation.resume()
            } else {
                waiters.append(continuation)
                lock.unlock()
            }
        }
    }

    func signal() {
        lock.lock()
        if let waiter = waiters.first {
            waiters.removeFirst()
            lock.unlock()
            waiter.resume()
        } else {
            pendingSignals += 1
            lock.unlock()
        }
    }
}

final class SpeechRecognitionPluginTests: XCTestCase {
    func testLegacyOnDeviceRequirement_notRequiredWhenFlagDisabled() {
        XCTAssertEqual(
            LegacyOnDeviceRecognitionRequirement.evaluate(
                useOnDeviceRecognition: false,
                supportsOnDeviceRecognition: false
            ),
            .notRequired
        )
        XCTAssertEqual(
            LegacyOnDeviceRecognitionRequirement.evaluate(
                useOnDeviceRecognition: false,
                supportsOnDeviceRecognition: true
            ),
            .notRequired
        )
    }

    func testLegacyOnDeviceRequirement_requiredWhenSupported() {
        XCTAssertEqual(
            LegacyOnDeviceRecognitionRequirement.evaluate(
                useOnDeviceRecognition: true,
                supportsOnDeviceRecognition: true
            ),
            .required
        )
    }

    func testLegacyOnDeviceRequirement_unavailableWhenRequestedButNotSupported() {
        XCTAssertEqual(
            LegacyOnDeviceRecognitionRequirement.evaluate(
                useOnDeviceRecognition: true,
                supportsOnDeviceRecognition: false
            ),
            .unavailable
        )
    }

    func testLegacyOnDeviceRequirement_unavailableErrorMetadataMatchesAndroid() {
        XCTAssertEqual(
            LegacyOnDeviceRecognitionRequirement.unavailableErrorCode,
            "ON_DEVICE_RECOGNITION_UNAVAILABLE"
        )
        XCTAssertEqual(
            LegacyOnDeviceRecognitionRequirement.unavailableErrorMessage,
            "On-device speech recognition is not available on this device."
        )
    }

    func testOnDeviceAvailabilityRoute_modernWhenModernOSAndLegacyNotPreferred() {
        XCTAssertEqual(
            OnDeviceRecognitionAvailabilityResolver.route(
                preferLegacyRecognizer: false,
                modernPathSupportedOnOS: true
            ),
            .modern
        )
    }

    func testOnDeviceAvailabilityRoute_legacyWhenLegacyPreferredOnModernOS() {
        XCTAssertEqual(
            OnDeviceRecognitionAvailabilityResolver.route(
                preferLegacyRecognizer: true,
                modernPathSupportedOnOS: true
            ),
            .legacy
        )
    }

    func testOnDeviceAvailabilityRoute_legacyWhenModernOSUnsupported() {
        XCTAssertEqual(
            OnDeviceRecognitionAvailabilityResolver.route(
                preferLegacyRecognizer: false,
                modernPathSupportedOnOS: false
            ),
            .legacy
        )
    }

    func testOnDeviceAvailabilityRoute_legacyWhenLegacyPreferredOnOlderOS() {
        XCTAssertEqual(
            OnDeviceRecognitionAvailabilityResolver.route(
                preferLegacyRecognizer: true,
                modernPathSupportedOnOS: false
            ),
            .legacy
        )
    }

    func testLegacyOnDeviceAvailability_falseWhenRecognizerMissing() {
        XCTAssertFalse(
            OnDeviceRecognitionAvailabilityResolver.legacyAvailability(
                recognizerExists: false,
                supportsOnDeviceRecognition: true
            )
        )
    }

    func testLegacyOnDeviceAvailability_falseWhenOnDeviceUnsupported() {
        XCTAssertFalse(
            OnDeviceRecognitionAvailabilityResolver.legacyAvailability(
                recognizerExists: true,
                supportsOnDeviceRecognition: false
            )
        )
    }

    func testLegacyOnDeviceAvailability_trueWhenOnDeviceSupported() {
        XCTAssertTrue(
            OnDeviceRecognitionAvailabilityResolver.legacyAvailability(
                recognizerExists: true,
                supportsOnDeviceRecognition: true
            )
        )
    }

    func testBeginRecognitionRouteAlignment_prefersModernOnlyWhenEligible() {
        let modernPathSupportedOnOS = true
        let preferLegacyRecognizer = false
        let useOnDeviceRecognition = true

        let availabilityRoute = OnDeviceRecognitionAvailabilityResolver.route(
            preferLegacyRecognizer: preferLegacyRecognizer,
            modernPathSupportedOnOS: modernPathSupportedOnOS
        )
        let beginRecognitionUsesModern =
            modernPathSupportedOnOS &&
            useOnDeviceRecognition &&
            !preferLegacyRecognizer

        XCTAssertEqual(availabilityRoute, .modern)
        XCTAssertTrue(beginRecognitionUsesModern)
    }

    func testLegacyRecognitionStopStrategy_waitsWhenTaskIsRunning() {
        XCTAssertEqual(
            LegacyRecognitionStopStrategy.resolve(hasRecognitionRequest: true, hasRecognitionTask: true),
            .waitForFinalResult
        )
    }

    func testLegacyRecognitionStopStrategy_immediateWhenNoTask() {
        XCTAssertEqual(
            LegacyRecognitionStopStrategy.resolve(hasRecognitionRequest: true, hasRecognitionTask: false),
            .immediateCleanup
        )
        XCTAssertEqual(
            LegacyRecognitionStopStrategy.resolve(hasRecognitionRequest: false, hasRecognitionTask: false),
            .immediateCleanup
        )
    }

    func testRecognitionStopTiming_legacyFallbackMatchesAndroidStopFallback() {
        XCTAssertEqual(RecognitionStopTiming.legacyStopFallbackTimeout, 2.0)
    }

    func testLegacyStopFallbackScheduling_skipsWhenFallbackAlreadyScheduled() {
        XCTAssertTrue(LegacyStopFallbackScheduling.shouldScheduleNewFallback(hasExistingFallback: false))
        XCTAssertFalse(LegacyStopFallbackScheduling.shouldScheduleNewFallback(hasExistingFallback: true))
    }

    func testLegacySpeechRecognitionBenignStopError_onlyForUserStop() {
        let noSpeech = NSError(domain: "kAFAssistantErrorDomain", code: 1110)
        let canceled = NSError(domain: "kLSRErrorDomain", code: 301)
        let other = NSError(domain: "kAFAssistantErrorDomain", code: 1100)

        XCTAssertTrue(LegacySpeechRecognitionBenignStopError.isBenignAfterUserStop(noSpeech, isUserStop: true))
        XCTAssertTrue(LegacySpeechRecognitionBenignStopError.isBenignAfterUserStop(canceled, isUserStop: true))
        XCTAssertFalse(LegacySpeechRecognitionBenignStopError.isBenignAfterUserStop(other, isUserStop: true))
        XCTAssertFalse(LegacySpeechRecognitionBenignStopError.isBenignAfterUserStop(noSpeech, isUserStop: false))
    }

    func testInFlightAudioBufferCounter_waitUntilIdleBlocksForPendingTapWork() async {
        let counter = InFlightAudioBufferCounter()
        counter.registerPendingWork()
        let idleReached = AsyncTestFlag()
        let registration = IdleWaiterRegistrationAwaiter()
        counter.idleWaiterRegistrationProbe.setHandler { registration.signal() }

        let waitRegistration = Task { await registration.waitForNext() }
        let idleTask = Task {
            await counter.waitUntilIdle()
            idleReached.set()
        }

        await waitRegistration.value
        XCTAssertFalse(idleReached.get())
        await counter.track {}

        await idleTask.value
        XCTAssertTrue(idleReached.get())
    }

    func testInFlightAudioBufferCounter_waitUntilIdleBlocksForInFlightWork() async {
        let counter = InFlightAudioBufferCounter()
        counter.registerPendingWork()
        let idleReached = AsyncTestFlag()
        let idleEarlyReached = AsyncTestFlag()
        let trackFinished = AsyncTestFlag()

        let registration = IdleWaiterRegistrationAwaiter()
        counter.idleWaiterRegistrationProbe.setHandler { registration.signal() }

        let waitFirstRegistration = Task { await registration.waitForNext() }
        let idleTask = Task {
            await counter.waitUntilIdle()
            idleReached.set()
        }

        let processingGate = AsyncTestGate()
        let processingStarted = Task {
            await counter.track {
                await processingGate.awaitRelease()
            }
            trackFinished.set()
        }

        await waitFirstRegistration.value
        XCTAssertFalse(idleReached.get())
        XCTAssertFalse(trackFinished.get())

        let waitSecondRegistration = Task { await registration.waitForNext() }
        let idleEarly = Task {
            await counter.waitUntilIdle()
            idleEarlyReached.set()
        }

        await waitSecondRegistration.value
        XCTAssertFalse(idleEarlyReached.get())

        processingGate.release()
        await processingStarted.value
        XCTAssertTrue(trackFinished.get())

        await idleTask.value
        await idleEarly.value
        XCTAssertTrue(idleReached.get())
        XCTAssertTrue(idleEarlyReached.get())
    }

    func testBeginRecognitionRouteAlignment_prefersLegacyWhenLegacyFlagSet() {
        let modernPathSupportedOnOS = true
        let preferLegacyRecognizer = true
        let useOnDeviceRecognition = true

        let availabilityRoute = OnDeviceRecognitionAvailabilityResolver.route(
            preferLegacyRecognizer: preferLegacyRecognizer,
            modernPathSupportedOnOS: modernPathSupportedOnOS
        )
        let beginRecognitionUsesModern =
            modernPathSupportedOnOS &&
            useOnDeviceRecognition &&
            !preferLegacyRecognizer

        XCTAssertEqual(availabilityRoute, .legacy)
        XCTAssertFalse(beginRecognitionUsesModern)
    }

    func testSpeechAnalyzerDeadline_returnsFalseWhenOperationNeverFinishes() async throws {
        guard #available(iOS 26.0, *) else {
            throw XCTSkip("SpeechAnalyzer needs iOS 26")
        }
        let neverReleased = AsyncTestGate()
        let timedOut = AsyncTestFlag()
        let started = ContinuousClock.now

        let finished = await SpeechAnalyzerDeadline.run(
            deadline: .milliseconds(200),
            onTimeout: { timedOut.set() },
            operation: { await neverReleased.awaitRelease() }
        )

        XCTAssertFalse(finished)
        XCTAssertTrue(timedOut.get())
        XCTAssertLessThan(ContinuousClock.now - started, .seconds(2))
        neverReleased.release()
    }

    func testSpeechAnalyzerDeadline_returnsTrueWhenOperationFinishesInTime() async throws {
        guard #available(iOS 26.0, *) else {
            throw XCTSkip("SpeechAnalyzer needs iOS 26")
        }
        let timedOut = AsyncTestFlag()

        let finished = await SpeechAnalyzerDeadline.run(
            deadline: .seconds(5),
            onTimeout: { timedOut.set() },
            operation: {}
        )

        XCTAssertTrue(finished)
        XCTAssertFalse(timedOut.get())
    }

    func testModernStopSettleOnce_onlyOneOfStopAndBackstopSettles() async {
        let once = ModernStopSettleOnce()

        let winners = await withTaskGroup(of: Bool.self) { group in
            for _ in 0..<64 {
                group.addTask { once.claim() }
            }
            var count = 0
            for await won in group where won {
                count += 1
            }
            return count
        }

        XCTAssertEqual(winners, 1)
        XCTAssertFalse(once.claim())
    }
}
