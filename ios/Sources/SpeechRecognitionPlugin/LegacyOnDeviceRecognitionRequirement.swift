import Foundation

enum LegacyOnDeviceRecognitionRequirement {
    case notRequired
    case required
    case unavailable

    static let unavailableErrorCode = "ON_DEVICE_RECOGNITION_UNAVAILABLE"
    static let unavailableErrorMessage = "On-device speech recognition is not available on this device."

    static func evaluate(
        useOnDeviceRecognition: Bool,
        supportsOnDeviceRecognition: Bool
    ) -> LegacyOnDeviceRecognitionRequirement {
        guard useOnDeviceRecognition else {
            return .notRequired
        }

        return supportsOnDeviceRecognition ? .required : .unavailable
    }
}
