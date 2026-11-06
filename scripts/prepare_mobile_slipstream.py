"""Make slipstream-client listen on a Unix socket for RawlineMobile.

A TCP listener on loopback is open to every app on the phone, so a tunnel
whose backend needs no password would carry anyone's traffic. The mobile
build listens on a Unix socket named by --tcp-listen-host instead; it sits in
the app's private session directory, which other apps cannot open. The DNS
transport, QUIC and resolver handling are untouched.
"""
from pathlib import Path
import sys

checkout = Path(sys.argv[1])
root = checkout / "crates/slipstream-client/src"

# The NDK toolchain limits library lookups to its sysroot; the vendored
# OpenSSL that picoquic links against is built outside of it.
script = checkout / "scripts/build_picoquic.sh"
source = script.read_text(encoding="utf-8")
marker = '  CMAKE_ARGS+=("-DCMAKE_TOOLCHAIN_FILE=${TOOLCHAIN_FILE}")\n'
if source.count(marker) != 1:
    raise SystemExit("build_picoquic.sh changed; update prepare_mobile_slipstream.py")
script.write_text(source.replace(marker, marker +
    '  CMAKE_ARGS+=("-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH" "-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH" "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH")\n'),
    encoding="utf-8", newline="\n")


def patch(name, replacements):
    path = root / name
    source = path.read_text(encoding="utf-8")
    for old, new in replacements:
        if source.count(old) != 1:
            raise SystemExit(f"slipstream-client {name} changed; update prepare_mobile_slipstream.py ({old!r})")
        source = source.replace(old, new)
    path.write_text(source, encoding="utf-8", newline="\n")


patch("runtime/setup.rs", [
    ("use tokio::net::{TcpListener as TokioTcpListener, UdpSocket as TokioUdpSocket};",
     "use tokio::net::{UnixListener as TokioTcpListener, UdpSocket as TokioUdpSocket};"),
    ("""    bind_first_resolved_with_ipv4_fallback(host, port, bind_tcp_listener_addr, "TCP listener").await
""",
     """    let _ = (port, bind_tcp_listener_addr);
    let _ = std::fs::remove_file(host);
    let listener = TokioTcpListener::bind(host)?;
    Ok((listener, host.to_owned()))
"""),
])
patch("streams/acceptor.rs", [
    ("use tokio::net::TcpListener as TokioTcpListener;", "use tokio::net::UnixListener as TokioTcpListener;"),
])
patch("streams/state.rs", [
    ("use tokio::net::TcpStream as TokioTcpStream;", "use tokio::net::UnixStream as TokioTcpStream;"),
])
patch("streams/io_tasks.rs", [
    ("tokio::net::tcp::OwnedReadHalf", "tokio::net::unix::OwnedReadHalf"),
    ("tokio::net::tcp::OwnedWriteHalf", "tokio::net::unix::OwnedWriteHalf"),
])
patch("streams/command_dispatch.rs", [
    ("            let _ = stream.set_nodelay(true);\n", ""),
])

# cargo-ndk points RUST_ANDROID_GRADLE_CC at a clang without a target, so the FFI's
# own C files came out for the build machine; use the ABI's NDK clang instead.
cc = checkout / "crates/slipstream-ffi/build/cc.rs"
source = cc.read_text(encoding="utf-8")
old = '''            env::var("RUST_ANDROID_GRADLE_CC")
                .or_else(|_| env::var("CC"))'''
if source.count(old) != 1:
    raise SystemExit("slipstream-ffi compiler lookup changed; update prepare_mobile_slipstream.py")
cc.write_text(source.replace(old, '''            env::var("QTHRONE_ANDROID_CC")
                .or_else(|_| env::var("RUST_ANDROID_GRADLE_CC"))
                .or_else(|_| env::var("CC"))'''), encoding="utf-8", newline="\n")
