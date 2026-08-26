#!/usr/bin/env bash
set -euo pipefail

pick_ios_simulator_destination() {
  python3 - <<'PY'
import json
import subprocess

result = subprocess.run(
    ["xcrun", "simctl", "list", "devices", "available", "-j"],
    check=True,
    capture_output=True,
    text=True,
)
data = json.loads(result.stdout)

preferred_names = [
    "iPhone 16",
    "iPhone 16 Pro",
    "iPhone 15",
    "iPhone 15 Pro",
    "iPhone 14",
    "iPhone SE (3rd generation)",
]

devices_by_name: dict[str, str] = {}
for runtime, devices in data.get("devices", {}).items():
    if "iOS" not in runtime or "Simulator" not in runtime:
        continue
    for device in devices:
        if not device.get("isAvailable"):
            continue
        name = device.get("name", "")
        if "iPhone" in name:
            devices_by_name[name] = device["udid"]

for preferred_name in preferred_names:
    if preferred_name in devices_by_name:
        print(f"platform=iOS Simulator,id={devices_by_name[preferred_name]}")
        raise SystemExit(0)

if devices_by_name:
    name, udid = next(iter(devices_by_name.items()))
    print(f"platform=iOS Simulator,id={udid}")
    raise SystemExit(0)

raise SystemExit("No available iPhone simulator found for iOS tests.")
PY
}

xcodebuild build \
  -scheme CapgoCapacitorSpeechRecognition \
  -destination generic/platform=iOS

destination="$(pick_ios_simulator_destination)"
echo "Running iOS unit tests on: ${destination}"

xcodebuild test \
  -scheme CapgoCapacitorSpeechRecognition \
  -destination "${destination}" \
  -enableCodeCoverage NO
