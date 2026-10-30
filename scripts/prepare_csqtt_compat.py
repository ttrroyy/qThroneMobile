"""Private configuration I/O for the original device-bound CSQTT protocol."""
from pathlib import Path
import subprocess
import sys

REVISION = "446293aa2e873ac5323ef6fd2316d9b81d966c11"
root = Path(sys.argv[1])
assert subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip() == REVISION
p = root / "rust-client/main.rs"
source = p.read_text(encoding="utf-8")


def replace(before, after):
    global source
    assert source.count(before) == 1, before
    source = source.replace(before, after, 1)


replace("std::env::args().map(normalize_cli_argument).collect()", """let mut args: Vec<String> = std::env::args().map(normalize_cli_argument).collect();
    if let Some(config) = mobile_config() { args.extend(mobile_arguments(&config)); }
    args""")
replace("let bootstrap = read_vk_js_bootstrap().await?;", """let bootstrap = match mobile_config().and_then(|c| c["vk_access_token"].as_str().map(str::to_owned)) {
            Some(token) if !token.is_empty() => vk_js_calls::Bootstrap { token },
            _ => read_vk_js_bootstrap().await?,
        };""")
source += r'''

fn mobile_config() -> Option<serde_json::Value> {
    let path = std::env::var("CSQTT_CONFIG_FILE").ok()?;
    let bytes = std::fs::read(path).unwrap_or_else(|_| {
        eprintln!("Cannot read private CSQTT configuration"); std::process::exit(2)
    });
    Some(serde_json::from_slice(&bytes).unwrap_or_else(|_| {
        eprintln!("Invalid private CSQTT configuration"); std::process::exit(2)
    }))
}

// These values only enter the in-process parser, never the OS process arguments.
fn mobile_arguments(config: &serde_json::Value) -> Vec<String> {
    let mut args = Vec::new();
    for (key, flag) in [("peer","peer"), ("listen","listen"), ("password","password"),
        ("device_id","device-id"), ("captcha_mode","captcha-mode"), ("vk_anon_path","vk-auth-mode"),
        ("fingerprint","fingerprint"), ("client_ids","client-ids"), ("salt","salt")] {
        if let Some(value) = config[key].as_str() { args.push(format!("--{flag}={value}")); }
    }
    let obfs = match config["obfs"].as_str().unwrap_or("audio") { "unified" => "audio", value => value };
    args.push(format!("--obfs={obfs}"));
    let transport = match config["turn_transport"].as_str().unwrap_or("udp") {
        "tcp_tls" => "tcp", value => value
    };
    args.push(format!("--turn-transport={transport}"));
    for (key, flag) in [("workers","workers"), ("generation","gen")] {
        if let Some(value) = config[key].as_u64() { args.push(format!("--{flag}={value}")); }
    }
    if let Some(hashes) = config["hashes"].as_array() {
        let values: Vec<_> = hashes.iter().filter_map(|v| v.as_str()).collect();
        args.push(format!("--vk={}",values.join(",")));
    }
    if config["hash_source"].as_str() == Some("auto_js") {
        args.push("--vk-hash-mode=auto_js".to_owned());
    }
    args
}

#[cfg(test)]
mod mobile_compat_tests {
    use super::*;

    #[test]
    fn private_adapter_keeps_device_identity_and_original_wire_contract() {
        let values = mobile_arguments(&serde_json::json!({
            "peer":"192.0.2.1:46000", "device_id":"original-device", "password":"secret",
            "hashes":["manual_hash_12345678"], "generation":42, "salt":"epoch",
            "workers":18, "obfs":"unified", "turn_transport":"tcp_tls", "captcha_mode":"wv"
        }));
        let parsed = Arguments::try_parse_from(std::iter::once("client".to_owned()).chain(values)).unwrap();
        assert_eq!(parsed.device_id,"original-device");
        assert_eq!(parsed.password,"secret");
        assert_eq!(parsed.generation,42);
        assert_eq!(parsed.salt,"epoch");
        assert_eq!(parsed.obfs,"audio");
        assert_eq!(parsed.turn_transport,"tcp");
        assert_eq!(parsed.captcha_mode,"wv");
        assert_eq!(protocol::config_request("9000",&parsed.device_id,&parsed.password,parsed.generation,
            &parsed.salt,1,Some(18)), "GETCONF:9000|original-device|secret|42|epoch|1|18|CSQTT-WIRE-3");
    }

    #[test]
    fn auto_js_remains_available_without_exposing_token_as_an_argument() {
        let values = mobile_arguments(&serde_json::json!({
            "hash_source":"auto_js", "vk_access_token":"vk1.private", "vk_anon_path":"auto_js"
        }));
        assert!(!values.iter().any(|value| value.contains("vk1.private")));
        let parsed = Arguments::try_parse_from(std::iter::once("client".to_owned()).chain(values)).unwrap();
        assert_eq!(parsed.vk_hash_mode,"auto_js");
        assert_eq!(parsed.vk_auth_mode,"auto_js");
    }
}
'''
p.write_text(source, encoding="utf-8", newline="\n")
