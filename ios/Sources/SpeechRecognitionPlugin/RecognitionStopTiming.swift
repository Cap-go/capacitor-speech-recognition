import Foundation

enum RecognitionStopTiming {
    /// Grace period after `endAudio()` before finishing the session if no final result arrives.
    static let legacyStopFallbackTimeout: TimeInterval = 2.0
}

enum LegacyRecognitionStopStrategy: Equatable {
    case waitForFinalResult
    case immediateCleanup

    static func resolve(hasRecognitionRequest: Bool, hasRecognitionTask: Bool) -> LegacyRecognitionStopStrategy {
        if hasRecognitionRequest && hasRecognitionTask {
            return .waitForFinalResult
        }
        return .immediateCleanup
    }
}

enum LegacyStopFallbackScheduling {
    static func shouldScheduleNewFallback(hasExistingFallback: Bool) -> Bool {
        !hasExistingFallback
    }
}

enum LegacySpeechRecognitionBenignStopError {
    /// Speech errors that commonly follow `endAudio()` on a silent capture after user stop.
    static func isBenignAfterUserStop(_ error: Error, stopReason: ListeningReason?) -> Bool {
        guard stopReason == .userStop else {
            return false
        }

        let nsError = error as NSError
        switch (nsError.domain, nsError.code) {
        case ("kLSRErrorDomain", 301), ("kAFAssistantErrorDomain", 1110):
            return true
        default:
            return false
        }
    }
}
