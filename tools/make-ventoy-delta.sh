#!/bin/sh
# Builds a compact patch that turns the NEWEST Ventoy VTOYEFI image into an
# OLDER one, so older Ventoy versions can be shipped for a few MB each
# instead of a full 32 MiB image.
#
#   tools/make-ventoy-delta.sh <newest ventoy.disk.img> <older ventoy.disk.img> <out.vcdiff>
#
# Needs xdelta3. The patch is made without secondary compression (-S none),
# which is what VcdiffDecoder.java reads. The result is checked by applying
# it again and comparing byte for byte.
set -eu

if [ "$#" -ne 3 ]; then
    echo "usage: $0 <newest.img> <older.img> <out.vcdiff>" >&2
    exit 2
fi

NEW="$1"
OLD="$2"
OUT="$3"

command -v xdelta3 >/dev/null 2>&1 || {
    echo "xdelta3 is required (apt install xdelta3)" >&2
    exit 1
}

# -B: source window large enough to see the whole 32 MiB image.
xdelta3 -9 -e -f -S none -B 67108864 -s "$NEW" "$OLD" "$OUT"

CHECK="$(mktemp)"
trap 'rm -f "$CHECK"' EXIT

xdelta3 -d -f -s "$NEW" "$OUT" "$CHECK"

cmp -s "$CHECK" "$OLD" || {
    echo "[ERROR] the patch does not reproduce the older image" >&2
    exit 1
}

echo "[OK] $OUT: $(wc -c < "$OUT") bytes (reproduces $(basename "$OLD") exactly)"
