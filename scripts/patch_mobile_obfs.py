"""Add the WireGuard obfuscators and the Phobos SOCKS5 outbound to the pinned core.

The package is the desktop core's internal/obfs (core-patches/obfs); the mobile box
context takes its registries, as the desktop's does.
"""
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, before: str, after: str):
    text = path.read_text()
    if text.count(before) != 1:
        raise ValueError(f"Pinned core changed; review {path.name}")
    path.write_text(text.replace(before, after), newline="\n")


def patch(core: Path):
    module = re.search(r"(?m)^module\s+(\S+)", (core / "go.mod").read_text())[1]
    target = core / "internal" / "obfs"
    target.mkdir(parents=True, exist_ok=True)
    for source in (ROOT / "core-patches" / "obfs").glob("*.go"):
        shutil.copyfile(source, target / source.name)
    context = core / "mobile" / "context.go"
    replace_once(context, "\tctx = box.Context(ctx, include.InboundRegistry(), include.OutboundRegistry(), include.EndpointRegistry(), dnsRegistry,",
                 "\toutbounds, endpoints := obfs.Registries()\n\tctx = box.Context(ctx, include.InboundRegistry(), outbounds, endpoints, dnsRegistry,")
    replace_once(context, "import (\n", f"import (\n\t\"{module}/internal/obfs\"\n")


if __name__ == "__main__":
    patch(Path(sys.argv[1]))
