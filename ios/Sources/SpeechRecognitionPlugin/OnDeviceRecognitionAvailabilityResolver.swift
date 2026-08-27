import Foundation
import Speech

enum OnDeviceRecognitionAvailabilityResolver {
    enum Route {
        case modern
        case legacy
    }

    static func route(
        preferLegacyRecognizer: Bool,
        modernPathSupportedOnOS: Bool
    ) -> Route {
        if modernPathSupportedOnOS && !preferLegacyRecognizer {
            return .modern
        }
        return .legacy
    }

    static func legacyAvailability(
        recognizerExists: Bool,
        supportsOnDeviceRecognition: Bool
    ) -> Bool {
        guard recognizerExists else {
            return false
        }
        return supportsOnDeviceRecognition
    }

    static func legacyAvailability(for locale: Locale) -> Bool {
        guard let recognizer = SFSpeechRecognizer(locale: locale) else {
            return false
        }
        return legacyAvailability(
            recognizerExists: true,
            supportsOnDeviceRecognition: recognizer.supportsOnDeviceRecognition
        )
    }
}
