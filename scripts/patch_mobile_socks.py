"""Patch the pinned sing SOCKS client for authenticated, source-bound loopback UDP.

Remote SOCKS clients retain upstream NAT-compatible behavior. Sources are copied from
the checksummed Go module cache; this never mutates a shared module cache.
"""
import json
import shutil
import stat
import subprocess
import sys
from pathlib import Path

MODULE = "github.com/sagernet/sing"
SOURCE = "github.com/throneproj/sing"
VERSION = "v0.9.4-0.20261024082846-e3e392877db8"
SUM = "h1:V3PK33YPQX6LACVpQ05UFTUKou6jm6bMUJ+sY/otZis="
MOD_SUM = "h1:K3Owt3xPhHugvlnlPPxZJ/exXdaJfEPOTNorGk4AXjo="


def replace_once(path: Path, before: str, after: str):
    text = path.read_text()
    if text.count(before) != 1:
        raise ValueError(f"Pinned SOCKS source changed; review {path.name}")
    path.write_text(text.replace(before, after), newline="\n")


def copy_writable(source, destination):
    path = Path(destination)
    if path.exists():
        path.chmod(stat.S_IREAD | stat.S_IWRITE)
    return shutil.copyfile(source, destination)


def patch(core: Path):
    current = json.loads(subprocess.check_output(["go", "mod", "edit", "-json"], cwd=core))
    replacement = next((item["New"] for item in current.get("Replace", [])
                        if item["Old"]["Path"] == MODULE), None)
    if replacement not in ({"Path": SOURCE, "Version": VERSION}, {"Path": "./.qthrone/sing"}):
        raise ValueError("Core sing replacement changed; review the pinned UDP patch")
    info = json.loads(subprocess.check_output(
        ["go", "mod", "download", "-json", SOURCE + "@" + VERSION], cwd=core))
    if info.get("Sum") != SUM or info.get("GoModSum") != MOD_SUM:
        raise ValueError("Pinned sing source checksum mismatch")
    target = core / ".qthrone" / "sing"
    shutil.copytree(info["Dir"], target, dirs_exist_ok=True, copy_function=copy_writable)
    client = target / "protocol" / "socks" / "client.go"
    before = "\tcase Version5:\n\t\tvar response socks5.Response"
    replace_once(client, before,
        "\tcase Version5:\n"
        "\t\tif command == socks5.CommandUDPAssociate && c.serverAddr.Addr.IsLoopback() {\n"
        "\t\t\treturn c.dialLoopbackUDP(ctx, tcpConn, address)\n"
        "\t\t}\n\t\tvar response socks5.Response")
    handshake = target / "protocol" / "socks" / "handshake.go"
    signature = "func ClientHandshake5(conn io.ReadWriter, command byte, destination M.Socksaddr, username string, password string) (socks5.Response, error) {"
    replace_once(handshake, signature,
        signature + "\n\treturn clientHandshake5(conn, command, destination, username, password, false)\n}\n\n"
        "func clientHandshake5(conn io.ReadWriter, command byte, destination M.Socksaddr, username string, password string, sourceBound bool) (socks5.Response, error) {")
    replace_once(handshake, "\tif command == socks5.CommandUDPAssociate {",
        "\tif command == socks5.CommandUDPAssociate && !sourceBound {")
    replace_once(handshake,
        "\tif authResponse.Method == socks5.AuthTypeUsernamePassword {",
        "\tif sourceBound && authResponse.Method != method {\n"
        "\t\treturn socks5.Response{}, E.New(\"socks5: server selected an unoffered authentication method\")\n"
        "\t}\n\tif authResponse.Method == socks5.AuthTypeUsernamePassword {")
    replace_once(handshake, "\t\tcase socks5.CommandUDPAssociate:\n\t\t\tvar (",
        "\t\tcase socks5.CommandUDPAssociate:\n"
        "\t\t\tvar authenticatedSource M.Socksaddr\n"
        "\t\t\tif authenticator != nil {\n"
        "\t\t\t\tauthenticatedSource, err = authenticatedLoopbackUDPSource(conn, request.Destination)\n"
        "\t\t\t\tif err != nil { return err }\n"
        "\t\t\t}\n\t\t\tvar (")
    replace_once(handshake, "\t\t\tserverConn := bufio.NewServerPacketConn(udpConn)",
        "\t\t\tif authenticatedSource.IsValid() {\n"
        "\t\t\t\tudpConn = &sourceBoundPacketConn{PacketConn: udpConn, source: authenticatedSource}\n"
        "\t\t\t}\n\t\t\tserverConn := bufio.NewServerPacketConn(udpConn)")
    helper_dir = target / "protocol" / "socks"
    helper_dir.chmod(helper_dir.stat().st_mode | stat.S_IWUSR)
    for source in (Path(__file__).resolve().parents[1] / "core-patches" / "socks").glob("*.go"):
        copy_writable(source, helper_dir / source.name)
    subprocess.run(["go", "mod", "edit", "-replace=" + MODULE + "=./.qthrone/sing"], cwd=core, check=True)


if __name__ == "__main__":
    patch(Path(sys.argv[1]).resolve())
