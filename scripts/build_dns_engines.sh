#!/usr/bin/env bash
# CottenDNS and Soar: their own clients behind the authenticated loopback SOCKS5 bridge,
# pinned and built apart from the other transports, which stay as they are.
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
test -x "$CC"
SOURCE=$(mktemp -d "${RUNNER_TEMP:?}/qthrone-dns.XXXXXX")
OUT="$ROOT/deployment/dns/$ABI"
mkdir -p "$OUT"
fetch_source() {
  local folder=$1 url=$2 revision=$3
  git init -q "$folder"
  git -C "$folder" fetch -q --depth=1 "$url" "$revision"
  git -C "$folder" checkout -q --detach FETCH_HEAD
  test "$(git -C "$folder" rev-parse HEAD)" = "$revision"
}
FLAGS='-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384'
COTTENDNS=7db7b73658c791cce62300d29647e4d369d6e1e4
fetch_source "$SOURCE/cottendns" https://github.com/TaJirax/cottenDNS.git "$COTTENDNS"
mkdir -p "$SOURCE/cottendns/cmd/qthrone-cottendns"
cp "$ROOT/carriers/cottendns/"*.go "$SOURCE/cottendns/cmd/qthrone-cottendns/"
(cd "$SOURCE/cottendns"; go build -trimpath -buildvcs=false -buildmode=pie -ldflags="$FLAGS" -o "$OUT/libcottendns.so" ./cmd/qthrone-cottendns)
cp "$SOURCE/cottendns/LICENSE" "$OUT/CottenDNS-LICENSE"
SOAR=fea80aae6c60a9f27d7358e5f967cbc9b57bb37d
fetch_source "$SOURCE/soar" https://github.com/getlantern/soar.git "$SOAR"
mkdir -p "$SOURCE/soar/cmd/qthrone-soar"
cp "$ROOT/carriers/soar/"*.go "$SOURCE/soar/cmd/qthrone-soar/"
(cd "$SOURCE/soar"; go build -trimpath -buildvcs=false -buildmode=pie -ldflags="$FLAGS" -o "$OUT/libsoar.so" ./cmd/qthrone-soar)
cp "$SOURCE/soar/LICENSE" "$OUT/Soar-LICENSE"
for binary in "$OUT/"*.so; do
  "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$binary" | grep -q 'Type:.*DYN'
done
python3 - "$ABI" "$OUT" "$COTTENDNS" "$SOAR" <<'PY'
import hashlib,json,subprocess,sys
from pathlib import Path
abi,out,cotten,soar=sys.argv[1],Path(sys.argv[2]),sys.argv[3],sys.argv[4]
commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
paths=sorted(p for folder in ('carriers/cottendns','carriers/soar') for p in Path(folder).glob('*.go'))
paths.append(Path('scripts/build_dns_engines.sh'))
inputs={p.as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(out/'DNS.json').write_text(json.dumps({'build_commit':commit,'abi':abi,'inputs':inputs},indent=2)+'\n')
(out/'SOURCES.txt').write_text(f'qThroneMobile DNS engines {commit}\nCottenDNS {cotten}\nSoar {soar}\nAndroid ABI {abi}\n')
(out/'SHA256SUMS').write_text(''.join(f'{hashlib.sha256((out/n).read_bytes()).hexdigest()}  {n}\n' for n in ('libcottendns.so','libsoar.so')))
PY
tar -C "$OUT" -czf "qthrone-dns-$(git rev-parse --short=8 HEAD)-$ABI.tar.gz" \
  libcottendns.so libsoar.so CottenDNS-LICENSE Soar-LICENSE DNS.json SOURCES.txt SHA256SUMS
