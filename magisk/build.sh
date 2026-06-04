#!/bin/bash
# Build Magisk module zip
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

OUTPUT="copperhead-gateway-magisk.zip"
rm -f "$OUTPUT"

zip -r "$OUTPUT" \
    module.prop \
    install.sh \
    META-INF/ \
    system/ \
    -x "*.DS_Store" "build.sh"

echo "Built: $OUTPUT"
echo "Flash this zip via Magisk Manager or TWRP."
