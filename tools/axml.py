"""Minimal Android binary XML (AXML) writer for AndroidManifest.xml (no aapt needed)."""
import struct, sys

ANDROID_NS = "http://schemas.android.com/apk/res/android"
RES_IDS = {
    "theme": 0x01010000, "label": 0x01010001, "icon": 0x01010002, "name": 0x01010003,
    "exported": 0x01010010, "screenOrientation": 0x0101001e,
    "minSdkVersion": 0x0101020c, "versionCode": 0x0101021b, "versionName": 0x0101021c,
    "targetSdkVersion": 0x01010270, "drawable": 0x01010199,
}
T_STRING, T_INT, T_BOOL = 0x03, 0x10, 0x12

# (tag, [(ns?, name, value)], children)
def A(name, value):
    return (True, name, value)

def manifest(pkg, label, activity):
    return ("manifest", [(False, "package", pkg), A("versionCode", 3), A("versionName", "1.2")], [
        ("uses-sdk", [A("minSdkVersion", 21), A("targetSdkVersion", 29)], []),
        ("uses-permission", [A("name", "android.permission.RECORD_AUDIO")], []),
        ("application", [A("label", label), A("icon", ("ref", 0x7f020000))], [
            ("activity", [A("name", activity), A("label", label), A("exported", True),
                          A("screenOrientation", 1)], [
                ("intent-filter", [], [
                    ("action", [A("name", "android.intent.action.MAIN")], []),
                    ("category", [A("name", "android.intent.category.LAUNCHER")], []),
                ]),
            ]),
        ]),
    ])

def build(root):
    # collect strings: resource-mapped attribute names first
    attr_names, others = [], []
    def walk(n):
        tag, attrs, kids = n
        for ns, name, val in attrs:
            if ns and name not in attr_names: attr_names.append(name)
        for k in kids: walk(k)
    walk(root)
    attr_names.sort(key=lambda x: RES_IDS[x])
    strings = list(attr_names)
    def add(s):
        if s not in strings: strings.append(s)
        return strings.index(s)
    add("android"); add(ANDROID_NS)
    def walk2(n):
        tag, attrs, kids = n
        add(tag)
        for ns, name, val in attrs:
            add(name)
            if isinstance(val, str): add(val)
        for k in kids: walk2(k)
    walk2(root)
    idx = strings.index

    # string pool (UTF-16)
    data = b""; offsets = []
    for s in strings:
        offsets.append(len(data))
        u = s.encode("utf-16-le")
        n = len(u) // 2
        assert n < 0x8000
        data += struct.pack("<H", n) + u + b"\0\0"
    while len(data) % 4: data += b"\0"
    hdr = 28
    strings_start = hdr + 4 * len(strings)
    pool = struct.pack("<HHIIIIII", 0x0001, hdr, strings_start + len(data), len(strings), 0, 0,
                       strings_start, 0)
    pool += b"".join(struct.pack("<I", o) for o in offsets) + data

    resmap = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(attr_names))
    resmap += b"".join(struct.pack("<I", RES_IDS[n]) for n in attr_names)

    body = b""
    NS_I, URI_I = idx("android"), idx(ANDROID_NS)
    body += struct.pack("<HHIIiII", 0x0100, 16, 24, 1, -1, NS_I, URI_I)
    line = [1]
    def elem(n):
        nonlocal body
        tag, attrs, kids = n
        line[0] += 1
        attrs = sorted(attrs, key=lambda a: RES_IDS.get(a[1], 0) if a[0] else -1)
        ab = b""
        for ns, name, val in attrs:
            nsi = URI_I if ns else 0xFFFFFFFF
            if isinstance(val, tuple):
                raw, typ, d = 0xFFFFFFFF, 0x01, val[1]
            elif isinstance(val, bool):
                raw, typ, d = 0xFFFFFFFF, T_BOOL, 0xFFFFFFFF if val else 0
            elif isinstance(val, int):
                raw, typ, d = 0xFFFFFFFF, T_INT, val
            else:
                raw, typ, d = idx(val), T_STRING, idx(val)
            ab += struct.pack("<IIIHBBI", nsi, idx(name), raw, 8, 0, typ, d)
        size = 16 + 20 + len(ab)
        body += struct.pack("<HHIIi", 0x0102, 16, size, line[0], -1)
        body += struct.pack("<iIHHHHHH", -1, idx(tag), 20, 20, len(attrs), 0, 0, 0) + ab
        for k in kids: elem(k)
        body += struct.pack("<HHIIiiI", 0x0103, 16, 24, line[0], -1, -1, idx(tag))
    elem(root)
    body += struct.pack("<HHIIiII", 0x0101, 16, 24, line[0], -1, NS_I, URI_I)

    content = pool + resmap + body
    return struct.pack("<HHI", 0x0003, 8, 8 + len(content)) + content

if __name__ == "__main__":
    out = build(manifest("ru.andrey.guitartuner", "Мой тюнер", "ru.andrey.guitartuner.MainActivity"))
    open(sys.argv[1], "wb").write(out)
    print("wrote", len(out), "bytes")
