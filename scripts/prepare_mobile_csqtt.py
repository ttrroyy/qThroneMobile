"""Pass the session identity the original CSQTT app gives every launch.

Runs after Rawline's script/prepare_csqtt.py. The server keeps one epoch per
device (generation + salt): a launch with a new pair replaces the device's old
sessions, a DISCONNECT ends every session of that epoch. Without them every
launch, probe included, shared the epoch 0/"" with the connected VPN.
"""
from pathlib import Path
import sys

main = Path(sys.argv[1]) / "rust-client/main.rs"
source = main.read_text(encoding="utf-8")
marker = '        args.extend(["--turn-transport".to_owned(),'
if source.count(marker) != 1:
    raise SystemExit("CSQTT argument adapter changed; update prepare_mobile_csqtt.py")
addition = '''        if let Some(generation) = config["generation"].as_u64() {
            args.extend(["--gen".to_owned(), generation.to_string()]);
        }
        if let Some(salt) = config["salt"].as_str() {
            args.push(format!("--salt={salt}"));
        }
        // The original Android app's TLS profile for VK requests.
        args.push("--fingerprint=firefox".to_owned());
'''
main.write_text(source.replace(marker, addition + marker), encoding="utf-8", newline="\n")
