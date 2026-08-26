import XCTest
@testable import SpeechRecognitionPlugin

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
}
