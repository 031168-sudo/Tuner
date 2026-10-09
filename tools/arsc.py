"""Hand-built resources.arsc + adaptive icon for the tuner (no aapt needed)."""
import struct, sys, os
sys.path.insert(0, os.path.dirname(__file__))
import axml

PKG = "ru.andrey.guitartuner"

def strpool(strings):
    data = b""; offs = []
    for s in strings:
        offs.append(len(data)); u = s.encode("utf-16-le")
        data += struct.pack("<H", len(u)//2) + u + b"\0\0"
    while len(data) % 4: data += b"\0"
    start = 28 + 4*len(strings)
    return struct.pack("<HHIIIIII", 1, 28, start+len(data), len(strings), 0, 0, start, 0) + \
        b"".join(struct.pack("<I", o) for o in offs) + data

def config(density=0, sdk=0):
    c = bytearray(64); struct.pack_into("<I", c, 0, 64)
    struct.pack_into("<H", c, 14, density); struct.pack_into("<H", c, 24, sdk)
    return bytes(c)

def type_chunk(tid, cfg, entries):  # entries: list of (keyIdx, globalStrIdx)
    n = len(entries); hs = 20 + len(cfg)
    start = hs + 4*n
    offs = b""; body = b""
    for k, v in entries:
        offs += struct.pack("<I", len(body))
        body += struct.pack("<HHI", 8, 0, k) + struct.pack("<HBBI", 8, 0, 0x03, v)
    return struct.pack("<HHIBBHII", 0x0201, hs, start+len(body), tid, 0, 0, n, start) + cfg + offs + body

def spec(tid, flags):
    return struct.pack("<HHIBBHI", 0x0202, 16, 16+4*len(flags), tid, 0, 0, len(flags)) + \
        b"".join(struct.pack("<I", f) for f in flags)

values = ["res/drawable-xxxhdpi-v4/ic_bg.png", "res/drawable-xxxhdpi-v4/ic_fg.png",
          "res/mipmap-xxxhdpi-v4/ic_launcher.png", "res/mipmap-anydpi-v26/ic_launcher.xml"]
types = strpool(["drawable", "mipmap"]); keys = strpool(["ic_bg", "ic_fg", "ic_launcher"])
chunks = spec(1, [0x100, 0x100]) + type_chunk(1, config(640, 4), [(0, 0), (1, 1)]) + \
         spec(2, [0x500]) + type_chunk(2, config(640, 4), [(2, 2)]) + type_chunk(2, config(0xFFFE, 26), [(2, 3)])
name = PKG.encode("utf-16-le").ljust(256, b"\0")
hs = 288
pkg_hdr_wo = struct.pack("<I", 0x7f) + name
type_off = hs; key_off = hs + len(types)
pkg_body = types + keys + chunks
pkg = struct.pack("<HHI", 0x0200, hs, hs+len(pkg_body)) + pkg_hdr_wo + struct.pack("<IIIII", type_off, 2, key_off, 3, 0) + pkg_body
assert len(pkg) == hs + len(pkg_body)
gp = strpool(values)
table = struct.pack("<HHII", 2, 12, 12+len(gp)+len(pkg), 1) + gp + pkg
out = sys.argv[1]
open(os.path.join(out, "resources.arsc"), "wb").write(table)

icon = ("adaptive-icon", [], [
    ("background", [axml.A("drawable", ("ref", 0x7f010000))], []),
    ("foreground", [axml.A("drawable", ("ref", 0x7f010001))], []),
])
os.makedirs(os.path.join(out, "res/mipmap-anydpi-v26"), exist_ok=True)
open(os.path.join(out, values[3]), "wb").write(axml.build(icon))
print("arsc", len(table))
