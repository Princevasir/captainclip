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
# App icon, drawn from the same design as the Android logo
mkdir -p "$APP/Contents/Resources"
ICONSET="$(mktemp -d)/AppIcon.iconset"
swift run -c release iconmaker "$ICONSET" >/dev/null
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
codesign --force --sign - "$APP"
echo "Built $APP"
