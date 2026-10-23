import Foundation

/// Tracks audio tap work from registration on the tap thread through MainActor processing.
internal final class InFlightAudioBufferCounter: Sendable {
    private let state = IdleState()

    private final class IdleState: @unchecked Sendable {
        private let lock = NSLock()
        private var pendingCount = 0
        private var inFlightCount = 0
        private var idleContinuations: [CheckedContinuation<Void, Never>] = []

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
                    lock.unlock()
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
