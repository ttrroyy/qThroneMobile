"""Compile production log filters with ICU, the regex engine used by Android."""
import ctypes
import ctypes.util
from pathlib import Path
import re
import sys
import unittest


class ParseError(ctypes.Structure):
    _fields_ = [("line", ctypes.c_int32), ("offset", ctypes.c_int32),
                ("before", ctypes.c_uint16 * 16), ("after", ctypes.c_uint16 * 16)]


class LogRedactorIcuTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        library = "icu.dll" if sys.platform == "win32" else ctypes.util.find_library("icui18n")
        if not library:
            raise RuntimeError("ICU is required to check Android regex compatibility")
        cls.icu = ctypes.CDLL(library)
        version = re.search(r"\.so\.(\d+)", library)
        suffix = "_" + version[1] if version else ""
        cls.open = getattr(cls.icu, "uregex_openC" + suffix)
        cls.open.argtypes = [ctypes.c_char_p, ctypes.c_uint32, ctypes.POINTER(ParseError), ctypes.POINTER(ctypes.c_int32)]
        cls.open.restype = ctypes.c_void_p
        cls.close = getattr(cls.icu, "uregex_close" + suffix)
        cls.close.argtypes = [ctypes.c_void_p]

    def compile(self, pattern):
        error, status = ParseError(), ctypes.c_int32()
        result = self.open(pattern.encode("utf-8"), 0, ctypes.byref(error), ctypes.byref(status))
        if result:
            self.close(result)
        return status.value, error.offset

    def test_production_filters_compile_on_android_engine(self):
        source = (Path(__file__).resolve().parents[1] /
                  "app/src/main/java/moe/matsuri/nb4a/utils/LogRedactor.kt").read_text(encoding="utf-8")
        constants, patterns = {}, {}
        pending = None
        for line in source.splitlines():
            declaration = re.search(r"val (\w+) = (Regex\(|\"\"\")", line)
            if declaration:
                pending = declaration[1]
            if pending and '"""' in line:
                literal = line.split('"""', 1)[1].rsplit('"""', 1)[0]
                for name, value in constants.items():
                    literal = literal.replace("$" + name, value)
                if "const val" in line:
                    constants[pending] = literal
                else:
                    patterns[pending] = literal
                pending = None
        self.assertIn("SECRET", patterns)
        self.assertGreater(len(patterns), 5)
        for name, pattern in patterns.items():
            with self.subTest(filter=name):
                self.assertEqual(self.compile(pattern)[0], 0, f"Invalid ICU pattern: {name}")

    def test_rejects_unescaped_object_delimiter(self):
        self.assertNotEqual(self.compile(r"\{[^{}]*+}")[0], 0)


if __name__ == "__main__":
    unittest.main()
