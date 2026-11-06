#!/usr/bin/env bash
set -euo pipefail
ABI=${1:?Android ABI is required}
ROOT=$(pwd)
case "$ABI" in
  arm64-v8a) ARCH=arm64; CLANG=aarch64-linux-android ;;
  armeabi-v7a) ARCH=arm; export GOARM=7; CLANG=armv7a-linux-androideabi ;;
  x86_64) ARCH=amd64; CLANG=x86_64-linux-android ;;
  *) exit 2 ;;
esac
export GOOS=android GOARCH="$ARCH" CGO_ENABLED=1
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${CLANG}24-clang"
OUT="$ROOT/deployment/sstp/$ABI"
mkdir -p "$OUT"
(cd carriers/sstp; go build -mod=readonly -trimpath -buildvcs=false -buildmode=pie \
  -ldflags='-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$OUT/libsstp.so" .)
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$OUT/libsstp.so" | grep -q 'Type:.*DYN'
cp carriers/sstp/LICENSE "$OUT/SSTP-LICENSE"
python3 - "$ABI" "$OUT" <<'PY'
import hashlib,json,subprocess,sys
from pathlib import Path
abi,out=sys.argv[1],Path(sys.argv[2])
commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
paths=sorted(p for p in Path('carriers/sstp').rglob('*') if p.is_file() and p.suffix in {'.go','.mod','.sum'} or p.as_posix()=='carriers/sstp/LICENSE')
paths.append(Path('scripts/build_sstp.sh'))
inputs={p.as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(out/'SSTP.json').write_text(json.dumps({'build_commit':commit,'abi':abi,'inputs':inputs},indent=2)+'\n')
(out/'SOURCES.txt').write_text(f'RawlineMobile SSTP {commit}\nAndroid ABI {abi}\n')
digest=hashlib.sha256((out/'libsstp.so').read_bytes()).hexdigest()
(out/'SHA256SUMS').write_text(f'{digest}  libsstp.so\n')
PY
tar -C "$OUT" -czf "rawline-sstp-$(git rev-parse --short=8 HEAD)-$ABI.tar.gz" \
  libsstp.so SSTP-LICENSE SSTP.json SOURCES.txt SHA256SUMS
