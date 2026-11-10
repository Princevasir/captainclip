#!/bin/bash
# Builds build/ClipBridge.app (ad-hoc signed). Usage: ./build-app.sh
set -euo pipefail
cd "$(dirname "$0")"
swift build -c release --product ClipBridge
APP=build/ClipBridge.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$(swift build -c release --show-bin-path)/ClipBridge" "$APP/Contents/MacOS/"
cp bundle/Info.plist "$APP/Contents/"
codesign --force --sign - "$APP"
echo "Built $APP"
