import hashlib
import io
import json
import tarfile
import unittest
from csqtt_compat_archive import ABIS, BINARIES, COMPAT_COMMIT, inspect_archive


class CompatibilityArchiveTest(unittest.TestCase):
    def fixture(self, abi):
        metadata = dict(build_commit="build", core_commit="core", csqtt_commit=COMPAT_COMMIT, abi=abi, inputs={"source": "digest"})
        elf_class, machine = ABIS[abi]
        binary = b"\x7fELF" + bytes((elf_class, 1)) + bytes(10) + (3).to_bytes(2, "little") + machine.to_bytes(2, "little")
        files = {name: binary for name in BINARIES}
        files.update({"CSQTT-Compat-LICENSE": b"original license", "SOURCES.txt": b"source pins",
                      "CSQTT-COMPAT.json": json.dumps(metadata).encode(),
                      "SHA256SUMS": "".join(hashlib.sha256(binary).hexdigest() + "  " + name + "\n" for name in sorted(BINARIES)).encode()})
        return metadata, files

    def archive(self, files):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode="w:gz") as archive:
            for name, content in files:
                member = tarfile.TarInfo(name)
                member.size = len(content)
                archive.addfile(member, io.BytesIO(content))
        return data.getvalue()

    def test_every_abi_requires_matching_elf_source_pin_and_checksums(self):
        for abi in ABIS:
            metadata, files = self.fixture(abi)
            self.assertEqual(inspect_archive(self.archive(files.items()), metadata), files)
            for field, value in (("build_commit", "other"), ("core_commit", "other"), ("csqtt_commit", "other"), ("inputs", {})):
                with self.assertRaises(ValueError):
                    inspect_archive(self.archive(files.items()), dict(metadata, **{field: value}))
            files["libcsqtt_compat.so"] += b"tampered"
            with self.assertRaises(ValueError):
                inspect_archive(self.archive(files.items()), metadata)

    def test_paths_duplicates_extra_files_and_other_architectures_are_rejected(self):
        metadata, files = self.fixture("arm64-v8a")
        for name in ("../escape", "folder/file", "libqwdtt.so", "unexpected"):
            with self.assertRaises(ValueError):
                inspect_archive(self.archive([*files.items(), (name, b"bad")]), metadata)
        files["libcsqtt_compat.so"] = files["libcsqtt_compat.so"][:18] + (62).to_bytes(2, "little")
        files["SHA256SUMS"] = "".join(hashlib.sha256(files[name]).hexdigest() + "  " + name + "\n" for name in sorted(BINARIES)).encode()
        with self.assertRaises(ValueError):
            inspect_archive(self.archive(files.items()), metadata)


if __name__ == "__main__":
    unittest.main()
