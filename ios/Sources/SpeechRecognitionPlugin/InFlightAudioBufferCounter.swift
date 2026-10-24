import Foundation

/// Test hook: fired after an idle waiter is registered on `idleContinuations`.
internal final class IdleWaiterRegistrationProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var handler: (@Sendable () -> Void)?

    func setHandler(_ handler: (@Sendable () -> Void)?) {
        lock.lock()
        self.handler = handler
        lock.unlock()
    }

    func notifyRegistered() {
        lock.lock()
        let handler = handler
        lock.unlock()
        handler?()
    }
}

/// Tracks audio tap work from registration on the tap thread through MainActor processing.
internal final class InFlightAudioBufferCounter: Sendable {
    private let state = IdleState()
    internal let idleWaiterRegistrationProbe = IdleWaiterRegistrationProbe()

    private final class IdleState: @unchecked Sendable {
        private let lock = NSLock()
        private var pendingCount = 0
        private var inFlightCount = 0
        private var idleContinuations: [CheckedContinuation<Void, Never>] = []
        weak var registrationProbe: IdleWaiterRegistrationProbe?

        func registerPending() {
            lock.lock()
            pendingCount += 1
            lock.unlock()
        }

        func beginProcessing() {
            lock.lock()
            if pendingCount > 0 {
                pendingCount -= 1
            }
            inFlightCount += 1
            lock.unlock()
        }

        func endProcessing() {
            lock.lock()
            inFlightCount -= 1
            resumeIdleWaitersIfNeeded()
            lock.unlock()
        }

        func waitUntilIdle() async {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                if pendingCount == 0 && inFlightCount == 0 {
                    lock.unlock()
                    continuation.resume()
                } else {
                    idleContinuations.append(continuation)
                    let probe = registrationProbe
                    lock.unlock()
                    probe?.notifyRegistered()
                }
            }
        }

        private func resumeIdleWaitersIfNeeded() {
            guard pendingCount == 0, inFlightCount == 0 else {
                return
            }
            let waiters = idleContinuations
            idleContinuations = []
            for waiter in waiters {
                waiter.resume()
            }
        }
    }

    init() {
        state.registrationProbe = idleWaiterRegistrationProbe
    }

    nonisolated func registerPendingWork() {
        state.registerPending()
    }

    func track(_ operation: () async -> Void) async {
        state.beginProcessing()
        await operation()
        state.endProcessing()
    }

    func waitUntilIdle() async {
        await state.waitUntilIdle()
    }
}
