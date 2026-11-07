#!/bin/sh
# Runs the pure-Java tests (no Android SDK needed):
#   tests/run.sh
set -eu
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
S=src/com/werismoln/multibooter
T=tests/com/werismoln/multibooter

echo "== UsbVerifier"
javac -nowarn -d "$OUT" $S/UsbVerifier.java $T/UsbVerifierTest.java
java -Xmx2g -cp "$OUT" com.werismoln.multibooter.UsbVerifierTest

echo "== VirtualDisk / ExFatFormatter / ExFatReader / UsbAnalyzer"
javac -nowarn -d "$OUT" \
    $S/VirtualDisk.java $S/ExFatFormatter.java $S/ExFatReader.java \
    $S/UsbAnalyzer.java $S/VentoyDetector.java $S/VentoyGpt.java \
    $T/VirtualDiskTest.java
java -Xmx1g -cp "$OUT" com.werismoln.multibooter.VirtualDiskTest
