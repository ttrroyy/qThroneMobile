"""Bind each authenticated local SOCKS UDP association to its prebound client port.

The mobile core advertises its already reserved loopback UDP socket. Never learn
the owner from an unauthenticated first datagram: every Android app shares the
same loopback IP. These guarded edits apply only to the pinned client sources.
"""
from pathlib import Path
import shutil
import sys


def replace_once(source: str, before: str, after: str) -> str:
    if source.count(before) != 1:
        raise ValueError("Pinned SOCKS implementation changed; review the mobile source-port patch")
    return source.replace(before, after, 1)


def prepare(kind: str, folder: Path):
    if kind == "qwdtt":
        path = folder / "bridge_socks.go"
        source = path.read_text(encoding="utf-8")
        source = replace_once(source, "socksUDPAssociation(ctx, c, dial)", "socksUDPAssociation(ctx, c, dial, address)")
        source = replace_once(source,
            "func socksUDPAssociation(parent context.Context, control net.Conn, dial bridgeDial) error {",
            """func socksUDPAssociation(parent context.Context, control net.Conn, dial bridgeDial, requestedAddress string) error {
	// A local IP identifies every Android app; only the port reserved before TCP authentication identifies this client.
	controlIP := control.RemoteAddr().(*net.TCPAddr).IP
	requestedHost, requestedPortText, addressErr := net.SplitHostPort(requestedAddress)
	requestedPort, portErr := strconv.Atoi(requestedPortText)
	requestedIP := net.ParseIP(requestedHost)
	if addressErr != nil || portErr != nil || requestedPort < 1 || requestedPort > 65535 || requestedIP == nil ||
		(!requestedIP.IsUnspecified() && !requestedIP.Equal(controlIP)) {
		_ = socksReply(control, 2, "")
		return fmt.Errorf("authenticated UDP association requires the client's reserved source port")
	}""")
        source = replace_once(source, "\tcontrolIP := control.RemoteAddr().(*net.TCPAddr).IP\n\tbuf := make([]byte, 65535)",
            "\tbuf := make([]byte, 65535)")
        source = replace_once(source,
            "if !source.IP.Equal(controlIP) || n < 4 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {",
            "if !source.IP.Equal(controlIP) || source.Port != requestedPort || n < 4 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {")
    elif kind == "wdttplus":
        path = folder / "socks5_server.go"
        source = path.read_text(encoding="utf-8")
        source = replace_once(source,
            "func (server *socks5Server) serveUDPAssociation(ctx context.Context, control net.Conn, request socksRequest) error {",
            """func (server *socks5Server) serveUDPAssociation(ctx context.Context, control net.Conn, request socksRequest) error {
	// The mobile core reserves this UDP port before authenticating. An unknown port would allow a different local app to win the first packet.
	if server.authEnabled && request.port == 0 {
		_ = writeSocksReply(control, socksReplyNotAllowed, nil)
		return fmt.Errorf("authenticated UDP association requires the client's reserved source port")
	}""")
    else:
        raise ValueError("Expected qwdtt or wdttplus")
    path.write_text(source, encoding="utf-8", newline="\n")
    # CUBIC and growing windows for the userspace TCP behind the SOCKS listener (netstack_tuning.go overlay).
    tuning = {
        "qwdtt": [
            ("socks_wg.go", '\t\treturn nil, nil, fmt.Errorf("CreateNetTUN: %w", err)\n\t}\n', "\ttuneNetstack(tnet)\n"),
            ("raw_netstack.go", "\t\treturn nil, nil, err\n\t}\n\treturn &rawPacketDevice{tun: dev}, network, nil\n", None),
        ],
        "wdttplus": [
            ("socks_wg.go", '\t\treturn nil, nil, fmt.Errorf("создание userspace TUN: %w", err)\n\t}\n', "\ttuneNetstack(tunnelNetwork)\n"),
        ],
    }
    for name, anchor, call in tuning[kind]:
        target = folder / name
        text = target.read_text(encoding="utf-8")
        if call is None:
            text = replace_once(text, anchor, anchor.replace("\treturn &raw", "\ttuneNetstack(network)\n\treturn &raw"))
        else:
            text = replace_once(text, anchor, anchor + call)
        target.write_text(text, encoding="utf-8", newline="\n")
    if kind == "qwdtt":
        bridge = folder / "csqtt_bridge.go"
        source = bridge.read_text(encoding="utf-8")
        source = replace_once(source,
            '\tinput, err := command.StdinPipe()',
            '''\tipc, err := newMobileCSQTTIPC(cache)
\tif err != nil { return err }
\tdefer ipc.Close()
\tcommand.Args = append(command.Args, "--tun-fd-uds", ipc.path)
\tinput, err := command.StdinPipe()''')
        source = replace_once(source, '''\taddress, err := net.ResolveUDPAddr("udp4", c.Listen)
\tif err != nil {
\t\tdevice.Close()
\t\treturn err
\t}
\tsocket, err := net.DialUDP("udp4", nil, address)
\tif err != nil {
\t\tdevice.Close()
\t\treturn err
\t}''', '''\tsocket, err := ipc.attach(ctx)
\tif err != nil {
\t\tdevice.Close()
\t\treturn err
\t}''')
        bridge.write_text(source, encoding="utf-8", newline="\n")
        relay = folder / "internal/csqtt/relay.go"
        source = relay.read_text(encoding="utf-8")
        source = replace_once(source, "socket *net.UDPConn)", "socket io.ReadWriteCloser)")
        relay.write_text(source, encoding="utf-8", newline="\n")
    tests = Path(__file__).resolve().parents[1] / "core-patches" / "carrier-socks" / kind
    for overlay in tests.glob("*.go"):
        shutil.copyfile(overlay, folder / overlay.name)


if __name__ == "__main__":
    prepare(sys.argv[1], Path(sys.argv[2]))
