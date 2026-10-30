#!/usr/bin/env bash
set -euo pipefail
ABI=${1:?Android ABI is required}
ROOT=$(pwd)
REF=$(sed -n 's/^THRONE_CORE_REF=//p' nb4a.properties | tr -d '\r[:space:]')
COMPAT=446293aa2e873ac5323ef6fd2316d9b81d966c11
SOURCE=$(mktemp -d "${RUNNER_TEMP:?}/csqtt-compat.XXXXXX")
OUT="$ROOT/deployment/csqtt-compat/$ABI"
mkdir -p "$OUT"
fetch_source() {
  git init -q "$1"
  git -C "$1" fetch -q --depth=1 "$2" "$3"
  git -C "$1" checkout -q --detach FETCH_HEAD
  test "$(git -C "$1" rev-parse HEAD)" = "$3"
}
fetch_source "$SOURCE/qThrone" https://github.com/ttrroyy/qThrone.git "$REF"
fetch_source "$SOURCE/compat" https://github.com/amurcanov/csqtt.git "$COMPAT"
python3 scripts/prepare_android_captcha.py "$SOURCE/qThrone/qwdtt"
python3 scripts/prepare_mobile_carrier_socks.py qwdtt "$SOURCE/qThrone/qwdtt"
python3 scripts/prepare_csqtt_rebirth_bridge.py "$SOURCE/qThrone/qwdtt"
python3 scripts/prepare_csqtt_compat_bridge.py "$SOURCE/qThrone/qwdtt"
(cd "$SOURCE/qThrone/qwdtt"; CGO_ENABLED=0 go test -run '^TestMobileSOCKS' -count=1 .)
python3 scripts/prepare_csqtt_compat.py "$SOURCE/compat"
python3 scripts/prepare_private_csqtt.py "$SOURCE/compat"
rustup toolchain install 1.97.1 --profile minimal
(cd "$SOURCE/compat/rust-client"; cargo +1.97.1 test --locked)
case "$ABI" in
  arm64-v8a) export GOARCH=arm64; CLANG=aarch64-linux-android; RUST=aarch64-linux-android ;;
  armeabi-v7a) export GOARCH=arm GOARM=7; CLANG=armv7a-linux-androideabi; RUST=armv7-linux-androideabi ;;
  x86_64) export GOARCH=amd64; CLANG=x86_64-linux-android; RUST=x86_64-linux-android ;;
  *) exit 2 ;;
esac
export GOOS=android CGO_ENABLED=1
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${CLANG}24-clang"
test -x "$CC"
(cd "$SOURCE/qThrone/qwdtt"; gofmt -w *.go; go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$OUT/libqwdtt.so" .)
rustup target add --toolchain 1.97.1 "$RUST"
cargo +1.97.1 install cargo-ndk --version 4.1.2 --locked --root "$RUNNER_TEMP/qthrone-cargo-tools"
export PATH="$RUNNER_TEMP/qthrone-cargo-tools/bin:$PATH"
export CARGO_TARGET_DIR="$SOURCE/rust-build"
(cd "$SOURCE/compat/rust-client"; cargo +1.97.1 ndk -t "$ABI" -P 26 build --release --locked)
cp "$CARGO_TARGET_DIR/$RUST/release/client" "$OUT/libcsqtt_compat.so"
cp "$SOURCE/compat/LICENSE" "$OUT/CSQTT-Compat-LICENSE"
printf 'qThrone %s\nCSQTT compatibility %s\nAndroid ABI %s\nPrivate IPC %s\n' "$REF" "$COMPAT" "$ABI" "$GITHUB_SHA" > "$OUT/SOURCES.txt"
python3 - "$OUT" "$REF" "$COMPAT" "$ABI" <<'PY'
from pathlib import Path
import json,os,sys
sys.path.insert(0,str(Path('scripts').resolve()))
from csqtt_compat_archive import input_hashes
metadata={'build_commit':os.environ['GITHUB_SHA'],'core_commit':sys.argv[2],'csqtt_commit':sys.argv[3],'abi':sys.argv[4],
          'inputs':input_hashes(Path.cwd())}
(Path(sys.argv[1])/'CSQTT-COMPAT.json').write_text(json.dumps(metadata,indent=2)+'\n')
PY
(cd "$OUT"; sha256sum *.so > SHA256SUMS)
for binary in "$OUT/"*.so; do
  "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$binary" | grep -q 'Type:.*DYN'
done
tar -czf "$ROOT/qthrone-csqtt-compat-${GITHUB_SHA:0:8}-$ABI.tar.gz" -C "$OUT" libqwdtt.so libcsqtt_compat.so CSQTT-Compat-LICENSE SOURCES.txt SHA256SUMS CSQTT-COMPAT.json
