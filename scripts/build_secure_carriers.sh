#!/usr/bin/env bash
set -euo pipefail
ABI=${1:?Android ABI is required}
ROOT=$(pwd)
REF=$(sed -n 's/^THRONE_CORE_REF=//p' nb4a.properties | tr -d '\r[:space:]')
WDTTPLUS=f706ccd373685bbde20cae33b2b7f400e8335ecc
CSQTT=2066156119a5afe7d63e104aefdc8e0849999d03
SOURCE=$(mktemp -d "${RUNNER_TEMP:?}/secure-carriers.XXXXXX")
OUT="$ROOT/deployment/secure-carriers/$ABI"
mkdir -p "$OUT"
fetch_source() {
  git init -q "$1"
  git -C "$1" fetch -q --depth=1 "$2" "$3"
  git -C "$1" checkout -q --detach FETCH_HEAD
  test "$(git -C "$1" rev-parse HEAD)" = "$3"
}
fetch_source "$SOURCE/Rawline" https://github.com/ttrroyy/qThrone.git "$REF"
fetch_source "$SOURCE/wdttplus" https://github.com/Ivan4537/WDTT-Plus.git "$WDTTPLUS"
fetch_source "$SOURCE/csqtt" https://github.com/ttrroyy/csqtt-rebirth.git "$CSQTT"
python3 scripts/prepare_android_captcha.py "$SOURCE/Rawline/qwdtt"
# The Android bridge launches the CSQTT executable from the application's native library directory.
python3 - "$SOURCE/Rawline/qwdtt/csqtt_bridge.go" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); text=p.read_text()
old='name := "csqtt-transport"'
assert text.count(old)==1
p.write_text(text.replace(old,old+'\n\tif runtime.GOOS == "android" { name = "libcsqtt_transport.so" }'),newline='\n')
PY
python3 scripts/prepare_mobile_carrier_socks.py qwdtt "$SOURCE/Rawline/qwdtt"
python3 scripts/prepare_csqtt_rebirth_bridge.py "$SOURCE/Rawline/qwdtt"
python3 scripts/prepare_mobile_carrier_socks.py wdttplus "$SOURCE/wdttplus/go_client"
(cd "$SOURCE/Rawline/qwdtt"; CGO_ENABLED=0 go test -run '^TestMobileSOCKS' -count=1 .)
(cd "$SOURCE/wdttplus/go_client"; CGO_ENABLED=0 go test -run '^TestMobileSOCKS' -count=1 .)
python3 "$ROOT/scripts/prepare_csqtt_rebirth.py" "$SOURCE/csqtt"
python3 "$ROOT/scripts/prepare_private_csqtt.py" "$SOURCE/csqtt"
rustup toolchain install 1.97.1 --profile minimal
(cd "$SOURCE/csqtt/rust-client"; cargo +1.97.1 test --locked)
case "$ABI" in
  arm64-v8a) export GOARCH=arm64; CLANG=aarch64-linux-android; RUST=aarch64-linux-android ;;
  armeabi-v7a) export GOARCH=arm GOARM=7; CLANG=armv7a-linux-androideabi; RUST=armv7-linux-androideabi ;;
  x86_64) export GOARCH=amd64; CLANG=x86_64-linux-android; RUST=x86_64-linux-android ;;
  *) exit 2 ;;
esac
export GOOS=android CGO_ENABLED=1
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${CLANG}24-clang"
test -x "$CC"
(cd "$SOURCE/Rawline/qwdtt"; gofmt -w bridge_socks.go creds.go csqtt_bridge.go bridge_config.go vk_account.go; go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$OUT/libqwdtt.so" .)
(cd "$SOURCE/wdttplus/go_client"; go build -buildvcs=false -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$OUT/libwdttplus.so" .)
rustup target add --toolchain 1.97.1 "$RUST"
cargo +1.97.1 install cargo-ndk --version 4.1.2 --locked --root "$RUNNER_TEMP/rawline-cargo-tools"
export PATH="$RUNNER_TEMP/rawline-cargo-tools/bin:$PATH"
export CARGO_TARGET_DIR="$SOURCE/rust-build"
(cd "$SOURCE/csqtt/rust-client"; cargo +1.97.1 ndk -t "$ABI" -P 26 build --release --locked)
cp "$CARGO_TARGET_DIR/$RUST/release/client" "$OUT/libcsqtt_transport.so"
cp "$SOURCE/Rawline/qwdtt/LICENSE" "$OUT/qWDTT-LICENSE"
cp "$SOURCE/wdttplus/LICENSE" "$OUT/WDTT-Plus-LICENSE"
cp "$SOURCE/csqtt/LICENSE" "$OUT/CSQTT-LICENSE"
printf 'Rawline %s\nWDTT-Plus %s\nCSQTT %s\nAndroid ABI %s\nLocal IPC hardening %s\n' "$REF" "$WDTTPLUS" "$CSQTT" "$ABI" "$GITHUB_SHA" > "$OUT/SOURCES.txt"
python3 - "$OUT" "$REF" "$WDTTPLUS" "$CSQTT" <<'PY'
from pathlib import Path
import hashlib,json,os,sys
paths=['scripts/build_secure_carriers.sh','scripts/prepare_android_captcha.py','scripts/prepare_mobile_carrier_socks.py','scripts/prepare_private_csqtt.py','scripts/prepare_csqtt_rebirth.py','scripts/prepare_csqtt_rebirth_bridge.py']
paths+=sorted(str(p).replace('\\','/') for p in Path('core-patches/carrier-socks').rglob('*.go'))
metadata={'build_commit':os.environ['GITHUB_SHA'],'core_commit':sys.argv[2],'wdtt_commit':sys.argv[3],'csqtt_commit':sys.argv[4],
          'inputs':{name:hashlib.sha256(Path(name).read_bytes()).hexdigest() for name in paths}}
(Path(sys.argv[1])/'SECURITY.json').write_text(json.dumps(metadata,indent=2)+'\n')
PY
(cd "$OUT"; sha256sum *.so > SHA256SUMS)
for binary in "$OUT/"*.so; do
  "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$binary" | grep -q 'Type:.*DYN'
done
tar -czf "$ROOT/rawline-secure-socks-${GITHUB_SHA:0:8}-$ABI.tar.gz" -C "$OUT" libqwdtt.so libwdttplus.so libcsqtt_transport.so qWDTT-LICENSE WDTT-Plus-LICENSE CSQTT-LICENSE SOURCES.txt SHA256SUMS SECURITY.json
