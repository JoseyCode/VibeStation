#!/bin/bash

# Default version if not specified (e.g. 0.0.1 for minor debug builds)
VERSION=${1:-"0.0.1"}
OUTPUT_DIR="/Users/joseyjackovitch/Desktop/VibeStation Versions"
APK_SOURCE="app/build/outputs/apk/debug/app-debug.apk"
APK_DEST="$OUTPUT_DIR/vs-$VERSION.apk"

mkdir -p "$OUTPUT_DIR"

echo "Building VibeStation Debug APK..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    if [ -f "$APK_SOURCE" ]; then
        echo "Copying and renaming APK to: $APK_DEST"
        cp "$APK_SOURCE" "$APK_DEST"
        echo "Success: Build archived as vs-$VERSION.apk"
    else
        echo "Error: Compiled APK not found at $APK_SOURCE"
        exit 1
    fi
else
    echo "Error: Gradle build failed."
    exit 1
fi
