#!/usr/bin/env bash
# MRTT: its original Rust client and the packet bridge built on the pinned helper sources.
set -euo pipefail
ABI=${1:?Android ABI is required}
ROOT=$(pwd)
REF=$(sed -n 's/^THRONE_CORE_REF=//p' nb4a.properties | tr -d '\r[:space:]')
MRTT=233eddc06a0a117d3b1cbd20386be2bb4300126e
SOURCE=$(mktemp -d "${RUNNER_TEMP:?}/mrtt.XXXXXX")
OUT="$ROOT/deployment/mrtt/$ABI"
mkdir -p "$OUT"
fetch_source() {
  git init -q "$1"
  git -C "$1" fetch -q --depth=1 "$2" "$3"
  git -C "$1" checkout -q --detach FETCH_HEAD
  test "$(git -C "$1" rev-parse HEAD)" = "$3"
}
fetch_source "$SOURCE/Rawline" https://github.com/ttrroyy/qThrone.git "$REF"
fetch_source "$SOURCE/mrtt" https://github.com/ttrroyy/MRTT.git "$MRTT"
BRIDGE="$SOURCE/Rawline/qwdtt"
python3 scripts/prepare_android_captcha.py "$BRIDGE"
python3 scripts/prepare_mobile_carrier_socks.py qwdtt "$BRIDGE"
python3 scripts/prepare_csqtt_rebirth_bridge.py "$BRIDGE"
cp carriers/mrtt/*.go "$BRIDGE/"
python3 - "$BRIDGE/main.go" <<'PY'
import sys
from pathlib import Path
p = Path(sys.argv[1])
text = p.read_text(encoding='utf-8')
marker = '\tif len(os.Args) == 3 && os.Args[1] == "-csqtt-config" {'
assert text.count(marker) == 1 and '-mrtt-config' not in text
p.write_text(text.replace(marker, '''\tif len(os.Args) == 3 && os.Args[1] == "-mrtt-config" {
\t\tif err := runMRTTBridge(os.Args[2]); err != nil {
\t\t\tlog.Printf("[MRTT] %v", err)
\t\t\tos.Exit(1)
\t\t}
\t\treturn
\t}
''' + marker), encoding='utf-8', newline='\n')
PY
(cd "$BRIDGE"; CGO_ENABLED=0 go test -run 'MRTT' -count=1 .)
python3 scripts/prepare_private_csqtt.py "$SOURCE/mrtt"
rustup toolchain install 1.97.1 --profile minimal
case "$ABI" in
  arm64-v8a) export GOARCH=arm64; CLANG=aarch64-linux-android; RUST=aarch64-linux-android ;;
  armeabi-v7a) export GOARCH=arm GOARM=7; CLANG=armv7a-linux-androideabi; RUST=armv7-linux-androideabi ;;
  x86_64) export GOARCH=amd64; CLANG=x86_64-linux-android; RUST=x86_64-linux-android ;;
  *) exit 2 ;;
esac
export GOOS=android CGO_ENABLED=1
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${CLANG}24-clang"
test -x "$CC"
(cd "$BRIDGE"; go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$OUT/libmrtt.so" .)
unset CC GOOS GOARCH GOARM CGO_ENABLED
rustup target add --toolchain 1.97.1 "$RUST"
cargo +1.97.1 install cargo-ndk --version 4.1.2 --locked --root "$RUNNER_TEMP/rawline-cargo-tools"
export PATH="$RUNNER_TEMP/rawline-cargo-tools/bin:$PATH"
export CARGO_TARGET_DIR="$SOURCE/rust-build"
(cd "$SOURCE/mrtt/rust-client"; cargo +1.97.1 ndk -t "$ABI" -P 26 build --release --locked)
cp "$CARGO_TARGET_DIR/$RUST/release/client" "$OUT/libmrtt_client.so"
cp "$SOURCE/mrtt/LICENSE" "$OUT/MRTT-LICENSE"
python3 - "$ABI" "$OUT" "$MRTT" "$REF" <<'PY'
import hashlib,json,subprocess,sys
from pathlib import Path
abi,out,mrtt,ref=sys.argv[1],Path(sys.argv[2]),sys.argv[3],sys.argv[4]
commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
paths=sorted(Path('carriers/mrtt').glob('*.go'))+[Path('scripts/build_mrtt.sh'),Path('scripts/prepare_private_csqtt.py')]
inputs={p.as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(out/'MRTT.json').write_text(json.dumps({'build_commit':commit,'abi':abi,'mrtt_commit':mrtt,'core_commit':ref,'inputs':inputs},indent=2)+'\n')
(out/'SOURCES.txt').write_text(f'RawlineMobile MRTT {commit}\nMRTT {mrtt}\nRawline {ref}\nAndroid ABI {abi}\n')
names=('libmrtt.so','libmrtt_client.so')
(out/'SHA256SUMS').write_text(''.join(f'{hashlib.sha256((out/n).read_bytes()).hexdigest()}  {n}\n' for n in names))
PY
for binary in "$OUT/"*.so; do
  "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$binary" | grep -q 'Type:.*DYN'
done
tar -C "$OUT" -czf "rawline-mrtt-$(git rev-parse --short=8 HEAD)-$ABI.tar.gz" libmrtt.so libmrtt_client.so MRTT-LICENSE MRTT.json SOURCES.txt SHA256SUMS
