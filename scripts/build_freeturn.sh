#!/usr/bin/env bash
set -euo pipefail
ABI=${1:?Android ABI is required}
ROOT=$(pwd)
FREETURN=6d857e1e847a38fdee3e150314fb37009e3c37a6
GVISOR=v0.0.0-20260906120324-45bde0d1defa
case "$ABI" in
  arm64-v8a) ARCH=arm64; CLANG=aarch64-linux-android ;;
  armeabi-v7a) ARCH=arm; export GOARM=7; CLANG=armv7a-linux-androideabi ;;
  x86_64) ARCH=amd64; CLANG=x86_64-linux-android ;;
  *) exit 2 ;;
esac
SOURCE=$(mktemp -d)
git init -q "$SOURCE"
git -C "$SOURCE" fetch -q --depth=1 https://github.com/hackdiaz-dev/free-turn-proxy.git "$FREETURN"
git -C "$SOURCE" checkout -q FETCH_HEAD
test "$(git -C "$SOURCE" rev-parse HEAD)" = "$FREETURN"
mkdir "$SOURCE/cmd/rawline"
cp carriers/freeturn/*.go "$SOURCE/cmd/rawline/"
(cd "$SOURCE"; go get "gvisor.dev/gvisor@$GVISOR"; cd cmd/rawline; go test -mod=mod -count=1 -timeout 300s .)
export GOOS=android GOARCH="$ARCH" CGO_ENABLED=1
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${CLANG}24-clang"
OUT="$ROOT/deployment/freeturn/$ABI"
mkdir -p "$OUT"
(cd "$SOURCE/cmd/rawline"; go build -mod=mod -trimpath -buildvcs=false -buildmode=pie \
  -ldflags='-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$OUT/libfreeturn.so" .)
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$OUT/libfreeturn.so" | grep -q 'Type:.*DYN'
cp "$SOURCE/LICENSE" "$OUT/FreeTurn-LICENSE"
python3 - "$ABI" "$OUT" "$FREETURN" <<'PY'
import hashlib,json,subprocess,sys
from pathlib import Path
abi,out,freeturn=sys.argv[1],Path(sys.argv[2]),sys.argv[3]
commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
paths=sorted(Path('carriers/freeturn').glob('*.go'))+[Path('scripts/build_freeturn.sh')]
inputs={p.as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(out/'FreeTurn.json').write_text(json.dumps({'build_commit':commit,'abi':abi,'inputs':inputs},indent=2)+'\n')
(out/'SOURCES.txt').write_text(f'RawlineMobile FreeTurn {commit}\nfree-turn-proxy {freeturn}\nAndroid ABI {abi}\n')
digest=hashlib.sha256((out/'libfreeturn.so').read_bytes()).hexdigest()
(out/'SHA256SUMS').write_text(f'{digest}  libfreeturn.so\n')
PY
tar -C "$OUT" -czf "rawline-freeturn-$(git rev-parse --short=8 HEAD)-$ABI.tar.gz" \
  libfreeturn.so FreeTurn-LICENSE FreeTurn.json SOURCES.txt SHA256SUMS
