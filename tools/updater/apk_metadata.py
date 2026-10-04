#!/usr/bin/env python3
"""Read the binary Android manifest without native SDK tools (also works on ARM hosts)."""
import json
import struct
import sys
import zipfile


def identity(path):
    with zipfile.ZipFile(path) as archive:
        data = archive.read("AndroidManifest.xml")
    strings, result = [], {}
    offset = 8
    while offset < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, offset)
        if size < header or size == 0 or offset + size > len(data):
            raise ValueError("Invalid binary manifest")
        if kind == 1:  # RES_STRING_POOL_TYPE
            count, _, flags, start = struct.unpack_from("<IIII", data, offset + 8)
            for index in range(count):
                pos = offset + start + struct.unpack_from("<I", data, offset + header + index * 4)[0]
                if flags & 0x100:
                    def length(at):
                        n = data[at]
                        return (((n & 0x7f) << 8) | data[at + 1], at + 2) if n & 0x80 else (n, at + 1)
                    _, pos = length(pos)
                    n, pos = length(pos)
                    strings.append(data[pos:pos + n].decode("utf-8"))
                else:
                    n = struct.unpack_from("<H", data, pos)[0]
                    pos += 2
                    if n & 0x8000:
                        n = ((n & 0x7fff) << 16) | struct.unpack_from("<H", data, pos)[0]
                        pos += 2
                    strings.append(data[pos:pos + n * 2].decode("utf-16-le"))
        elif kind == 0x102:  # RES_XML_START_ELEMENT_TYPE
            name = strings[struct.unpack_from("<I", data, offset + 20)[0]]
            start, width, count = struct.unpack_from("<HHH", data, offset + 24)
            attrs = {}
            for index in range(count):
                pos = offset + 16 + start + index * width
                _, key, raw, _, _, value_type, value = struct.unpack_from("<IIIHBBI", data, pos)
                attrs[strings[key]] = strings[raw] if raw != 0xffffffff else (strings[value] if value_type == 3 else value)
            if name == "manifest":
                result.update({k: attrs[k] for k in ("package", "versionCode", "versionName")})
            if name == "meta-data" and attrs.get("name", "").startswith("com.glance."):
                result[attrs["name"].split(".")[-1]] = attrs["value"]
        offset += size
    return result


def validate(apk, build):
    actual = identity(apk)
    if type(build.get("versionCode")) is not int or not 1 <= build["versionCode"] <= 2100000000:
        raise ValueError("Invalid Android installation number")
    kind = build.get("kind", "normal")
    if kind not in ("normal", "restore") or actual.get("UPDATE_KIND", "normal") != kind:
        raise ValueError("APK operation differs from build.json")
    expected = {"package": "com.glance", "versionCode": build["versionCode"], "versionName": build["versionName"]}
    for field, key in [("codeIdentity", "CODE_IDENTITY"), ("dataContract", "DATA_CONTRACT"), ("kind", "UPDATE_KIND")]:
        if field in build:
            expected[key] = build[field]
    if any(actual.get(k) != v for k, v in expected.items()):
        raise ValueError("APK identity differs from build.json")
    if build.get("kind", "normal") == "restore" and not all(build.get(k) for k in ("codeIdentity", "dataContract")):
        raise ValueError("Recovery requires code identity and data contract")
    return actual


if __name__ == "__main__":
    with open(sys.argv[2]) as source:
        print(json.dumps(validate(sys.argv[1], json.load(source))))
