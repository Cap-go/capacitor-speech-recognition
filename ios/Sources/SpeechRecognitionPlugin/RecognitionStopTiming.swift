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
