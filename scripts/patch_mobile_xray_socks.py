"""Reserve the UDP source before authenticating Xray's local SOCKS bridges.

Only literal loopback SOCKS servers use this path; remote SOCKS keeps upstream
behavior. Work on a checksummed private source copy, never the shared Go cache.
"""
import json
import shutil
import stat
import subprocess
import sys
from pathlib import Path

MODULE = "github.com/xtls/xray-core"
SOURCE = "github.com/throneproj/xray-core"
VERSION = "v1.251015.1-0.20261023204031-c4260cc24739"
SUM = "h1:6zB5CPdy4pg/fvtF5sqlgVdd0yFr8k9e+2Vfx7fkFlE="
MOD_SUM = "h1:V3ZV2lo9eo7BRqns6hBjiOjNty3rH9jCYXQci3vYkp8="


def replace_once(path: Path, before: str, after: str):
    source = path.read_text(encoding="utf-8")
    if source.count(before) != 1:
        raise ValueError(f"Pinned Xray SOCKS source changed; review {path.name}")
    path.write_text(source.replace(before, after, 1), encoding="utf-8", newline="\n")


def copy_writable(source, destination):
    path = Path(destination)
    if path.exists():
        path.chmod(stat.S_IREAD | stat.S_IWRITE)
    return shutil.copyfile(source, destination)


def patch(core: Path):
    info = json.loads(subprocess.check_output(
        ["go", "mod", "download", "-json", SOURCE + "@" + VERSION], cwd=core))
    if info.get("Sum") != SUM or info.get("GoModSum") != MOD_SUM:
        raise ValueError("Pinned Xray source checksum mismatch")
    target = core / ".qthrone" / "xray-core"
    shutil.copytree(info["Dir"], target, dirs_exist_ok=True, copy_function=copy_writable)
    client = target / "proxy" / "socks" / "client.go"
    replace_once(client,
        "\tif err := conn.SetDeadline(time.Now().Add(p.Timeouts.Handshake)); err != nil {",
        """\tvar mobileUDP *mobileSOCKSUDPConn
	handshakeRequest := request
	if request.Command == protocol.RequestCommandUDP && dest.Address.Family().IsIP() && dest.Address.IP().IsLoopback() {
		var err error
		mobileUDP, err = newMobileSOCKSUDP(ctx, conn.LocalAddr(), conn.RemoteAddr())
		if err != nil { return errors.New("failed to reserve local SOCKS UDP source").Base(err) }
		defer mobileUDP.Close()
		local := mobileUDP.LocalAddr().(*net.UDPAddr)
		boundRequest := *request
		boundRequest.Address = net.IPAddress(local.IP)
		boundRequest.Port = net.Port(local.Port)
		handshakeRequest = &boundRequest
	}
	if err := conn.SetDeadline(time.Now().Add(p.Timeouts.Handshake)); err != nil {""")
    replace_once(client, "udpRequest, err := ClientHandshake(request, conn, conn)",
        "udpRequest, err := clientHandshake(handshakeRequest, conn, conn, mobileUDP != nil)")
    replace_once(client,
        "\t\tudpConn, err := dialer.Dial(ctx, udpRequest.Destination())",
        """\t\tvar udpConn net.Conn
		var err error
		if mobileUDP != nil {
			err = mobileUDP.bindRemote(udpRequest.Address.IP(), int(udpRequest.Port))
			udpConn = mobileUDP
		} else {
			udpConn, err = dialer.Dial(ctx, udpRequest.Destination())
		}""")
    protocol = target / "proxy" / "socks" / "protocol.go"
    signature = "func ClientHandshake(request *protocol.RequestHeader, reader io.Reader, writer io.Writer) (*protocol.RequestHeader, error) {"
    replace_once(protocol, signature,
        signature + "\n\treturn clientHandshake(request, reader, writer, false)\n}\n\n"
        "func clientHandshake(request *protocol.RequestHeader, reader io.Reader, writer io.Writer, sourceBound bool) (*protocol.RequestHeader, error) {")
    replace_once(protocol,
        "\tif request.Command == protocol.RequestCommandUDP {\n\t\tcommon.Must2(b.Write([]byte{1, 0, 0, 0, 0, 0, 0 /* RFC 1928 */}))",
        "\tif request.Command == protocol.RequestCommandUDP && !sourceBound {\n\t\tcommon.Must2(b.Write([]byte{1, 0, 0, 0, 0, 0, 0 /* RFC 1928 */}))")
    helper_dir = target / "proxy" / "socks"
    helper_dir.chmod(helper_dir.stat().st_mode | stat.S_IWUSR)
    for source in (Path(__file__).resolve().parents[1] / "core-patches" / "xray-socks").glob("*.go"):
        copy_writable(source, helper_dir / source.name)
    subprocess.run(["go", "mod", "edit", "-replace=" + MODULE + "=./.qthrone/xray-core"], cwd=core, check=True)


if __name__ == "__main__":
    patch(Path(sys.argv[1]).resolve())
