import AVFoundation
import Foundation
import QuartzCore

/// Shared RMS→dB→0..1 metering used by both legacy SFSpeechRecognizer and
/// modern SpeechAnalyzer recognition paths so calibration stays in sync.
enum AudioLevelMetering {
    /// Target emit rate (~15 Hz), matching the public audioLevel docs.
    static let emitInterval: CFTimeInterval = 1.0 / 15.0

    /// Map PCM buffer energy into a normalized 0..1 level.
    /// Typical speech sits roughly between -50 dBFS and 0 dBFS.
    static func normalizedAudioLevel(from buffer: AVAudioPCMBuffer) -> Float {
        guard let channelData = buffer.floatChannelData?[0] else {
            return 0
        }
        let frameLength = Int(buffer.frameLength)
        guard frameLength > 0 else {
            return 0
        }

        var sumSquares: Float = 0
        for i in 0..<frameLength {
            let sample = channelData[i]
            sumSquares += sample * sample
        }
        let rms = sqrt(sumSquares / Float(frameLength))
        let db = 20 * log10(max(rms, 1e-7))
        return max(0, min(1, (db + 50) / 50))
    }

    /// Returns true when enough time has elapsed since `lastEmit`, and updates it.
    static func shouldEmit(now: CFTimeInterval, lastEmit: inout CFTimeInterval) -> Bool {
        guard now - lastEmit >= emitInterval else {
            return false
        }
        lastEmit = now
        return true
    }
}
