#!/bin/bash
# Builds build/ClipBridge.app (ad-hoc signed). Usage: ./build-app.sh
set -euo pipefail
cd "$(dirname "$0")"
# Universal binary: Apple silicon + Intel (built separately, joined with lipo — no Xcode needed)
swift build -c release --product ClipBridge
swift build -c release --product ClipBridge --triple x86_64-apple-macosx13.0
APP=build/ClipBridge.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
lipo -create -output "$APP/Contents/MacOS/ClipBridge" \
  "$(swift build -c release --show-bin-path)/ClipBridge" \
  "$(swift build -c release --triple x86_64-apple-macosx13.0 --show-bin-path)/ClipBridge"
cp bundle/Info.plist "$APP/Contents/"
# App icon, drawn from the same design as the Android logo
mkdir -p "$APP/Contents/Resources"
ICONSET="$(mktemp -d)/AppIcon.iconset"
swift run -c release iconmaker "$ICONSET" >/dev/null
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
codesign --force --sign - "$APP"
echo "Built $APP"
