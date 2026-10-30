"""Validate finished native jobs, store immutable archives and pin their exact inputs."""
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

from csqtt_compat_archive import ABIS, COMPAT_COMMIT, input_hashes, inspect_archive


def main():
    root = Path(__file__).resolve().parent.parent
    lock_path = root / "native-transports.lock.json"
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    component = dict(build_commit=os.environ["GITHUB_SHA"], core_commit=lock["core_commit"],
                     csqtt_commit=COMPAT_COMMIT, inputs=input_hashes(root))
    archives = list(Path(sys.argv[1]).rglob("qthrone-csqtt-compat-*.tar.gz"))
    if len(archives) != len(ABIS):
        raise ValueError("Expected one native archive per ABI")
    repo = os.environ["GH_REPO"]
    storage = json.loads(subprocess.check_output(
        ["gh", "api", f"repos/{repo}/releases/tags/v1.0.0"], text=True))
    existing = {asset["name"]: asset.get("digest") for asset in storage["assets"]}
    records = []
    for abi in ABIS:
        name = f"qthrone-csqtt-compat-{component['build_commit'][:8]}-{abi}.tar.gz"
        archive = next((p for p in archives if p.name == name), None)
        if archive is None:
            raise ValueError("Missing native archive: " + name)
        data = archive.read_bytes()
        files = inspect_archive(data, dict(component, abi=abi))
        digest = hashlib.sha256(data).hexdigest()
        if name in existing:
            if existing[name] != "sha256:" + digest:
                raise ValueError("Refusing to overwrite an immutable native archive")
        else:
            subprocess.run(["gh", "release", "upload", "v1.0.0", str(archive)], check=True)
        records.append(dict(abi=abi, url=f"https://github.com/{repo}/releases/download/v1.0.0/{name}",
                            sha256=digest, sources=files["SOURCES.txt"].decode()))
    lock["csqtt_compat"] = dict(component, archives=records)
    lock_path.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
