import json
import shutil
import stat
import subprocess
import sys
from pathlib import Path


def patch(core: Path):
    module = "github.com/xchacha20-poly1305/sing-trusttunnel"
    info = json.loads(subprocess.check_output(["go", "mod", "download", "-json", module + "@v0.3.2"], cwd=core))
    lock = json.loads((Path(__file__).resolve().parents[1] / "core-dependencies.json").read_text())[module]
    if info.get("Sum") != lock["sum"] or info.get("GoModSum") != lock["mod_sum"]:
        raise ValueError("TrustTunnel source checksum mismatch")
    target = core / ".qthrone" / "trusttunnel"
    target.mkdir(parents=True, exist_ok=True)
    for source in Path(info["Dir"]).glob("*"):
        if source.is_file():
            destination = target / source.name
            if destination.exists():
                destination.chmod(stat.S_IREAD | stat.S_IWRITE)
            shutil.copyfile(source, destination)
    client = target / "client.go"
    content = client.read_text()
    before = "\treturn client, nil\n}"
    if content.count(before) != 1:
        raise ValueError("TrustTunnel constructor changed; review fallback hook")
    client.write_text(content.replace(before, "\tif options.QUIC {\n\t\tif err = client.enableHTTPSFallback(options); err != nil { return nil, err }\n\t}\n" + before), newline="\n")
    for source in (Path(__file__).resolve().parents[1] / "core-patches" / "trusttunnel").glob("*.go"):
        shutil.copyfile(source, target / source.name)
    subprocess.run(["go", "mod", "edit", "-replace=" + module + "=./.qthrone/trusttunnel"], cwd=core, check=True)
    for source in (Path(__file__).resolve().parents[1] / "core-patches" / "mobile").glob("*.go"):
        shutil.copyfile(source, core / "mobile" / source.name)
    resolver = core / "internal" / "resolve"
    resolver.mkdir(parents=True, exist_ok=True)
    for source in (Path(__file__).resolve().parents[1] / "core-patches" / "resolve").glob("*.go"):
        shutil.copyfile(source, resolver / source.name)
    geodata = core / "internal" / "providergeo"
    geodata.mkdir(parents=True, exist_ok=True)
    for source in (Path(__file__).resolve().parents[1] / "core-patches" / "providergeo").glob("*.go"):
        shutil.copyfile(source, geodata / source.name)


if __name__ == "__main__":
    patch(Path(sys.argv[1]).resolve())
