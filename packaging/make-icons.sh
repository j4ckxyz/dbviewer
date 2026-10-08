#!/bin/sh
# Regenerates DBViewer.icns, DBViewer.ico and the in-app PNG from icon.svg. macOS only.
set -e
cd "$(dirname "$0")"
tmp=$(mktemp -d)
cat > "$tmp/render.swift" <<'SWIFT'
import AppKit
let args = CommandLine.arguments
let svg = NSImage(contentsOfFile: args[1])!
for size in args[3...].compactMap({ Int($0) }) {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = NSSize(width: size, height: size)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    svg.draw(in: NSRect(x: 0, y: 0, width: size, height: size))
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "\(args[2])/\(size).png"))
}
SWIFT
swift "$tmp/render.swift" icon.svg "$tmp" 16 32 48 64 128 256 512 1024

set=$tmp/DBViewer.iconset
mkdir "$set"
for s in 16 32 128 256 512; do
  cp "$tmp/$s.png" "$set/icon_${s}x${s}.png"
  cp "$tmp/$((s * 2)).png" "$set/icon_${s}x${s}@2x.png"
done
iconutil -c icns "$set" -o DBViewer.icns

python3 - "$tmp" <<'PY'
import struct, sys
sizes = [16, 32, 48, 64, 128, 256]
blobs = [open(f"{sys.argv[1]}/{s}.png", "rb").read() for s in sizes]
out = struct.pack("<HHH", 0, 1, len(sizes))
offset = 6 + 16 * len(sizes)
for s, b in zip(sizes, blobs):
    out += struct.pack("<BBBBHHII", s % 256, s % 256, 0, 0, 1, 32, len(b), offset)
    offset += len(b)
open("DBViewer.ico", "wb").write(out + b"".join(blobs))
PY

cp "$tmp/256.png" ../src/main/resources/icon.png
rm -rf "$tmp"
