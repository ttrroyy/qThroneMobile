"""Shared provenance checks for the compatibility build and its APK staging."""
import hashlib
import io
import json
import tarfile
from pathlib import Path

COMPAT_COMMIT = "446293aa2e873ac5323ef6fd2316d9b81d966c11"
ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62)}
BINARIES = {"libqwdtt.so", "libcsqtt_compat.so"}


def input_hashes(root):
    names = ["scripts/build_csqtt_compat.sh", "scripts/prepare_android_captcha.py",
             "scripts/prepare_mobile_carrier_socks.py", "scripts/prepare_csqtt_rebirth_bridge.py",
             "scripts/prepare_csqtt_compat_bridge.py", "scripts/prepare_csqtt_compat.py",
             "scripts/prepare_private_csqtt.py", "scripts/csqtt_compat_archive.py"]
    names += sorted(p.relative_to(root).as_posix() for p in (root / "core-patches/carrier-socks/qwdtt").rglob("*.go"))
    return {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in names}


def inspect_archive(data, expected):
    files = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for member in archive.getmembers():
            if not member.isfile() or "/" in member.name or "\\" in member.name or member.name in files:
                raise ValueError("Unsafe or duplicate compatibility archive entry")
            files[member.name] = archive.extractfile(member).read()
    if set(files) != BINARIES | {"CSQTT-Compat-LICENSE", "SOURCES.txt", "SHA256SUMS", "CSQTT-COMPAT.json"}:
        raise ValueError("Unexpected compatibility archive files")
    metadata = json.loads(files["CSQTT-COMPAT.json"])
    if metadata != expected:
        raise ValueError("Compatibility build provenance mismatch")
    if metadata["csqtt_commit"] != COMPAT_COMMIT:
        raise ValueError("Unexpected compatibility source pin")
    sums = dict(line.split()[::-1] for line in files["SHA256SUMS"].decode().splitlines())
    if set(sums) != BINARIES:
        raise ValueError("Missing compatibility binary checksum")
    elf_class, machine = ABIS[metadata["abi"]]
    for name, digest in sums.items():
        binary = files[name]
        if hashlib.sha256(binary).hexdigest() != digest:
            raise ValueError("Compatibility binary checksum mismatch")
        if len(binary) < 20 or binary[:6] != b"\x7fELF" + bytes((elf_class, 1)) or int.from_bytes(binary[16:18], "little") != 3 or int.from_bytes(binary[18:20], "little") != machine:
            raise ValueError("Wrong compatibility binary architecture or ELF type")
    return files
