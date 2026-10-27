#!/usr/bin/env bash
# CottenDNS, Soar, OpenFlux and the wlruscfd/openflux-server fork (OpenFlux Key): their own
# clients behind the authenticated loopback SOCKS5 bridge, pinned and built apart from the other
# transports, which stay as they are. This libopenflux.so replaces the one of the main archive.
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
# OpenFlux through its own mobile package, as the original Android app runs it (and as the main archive built it).
FLUX=edcf77a1848a72370cfba9f346d3b897b61e7e7d
fetch_source "$SOURCE/openflux" https://github.com/p1neappleXpress/OpenFlux.git "$FLUX"
mkdir -p "$SOURCE/openflux/mobile/cmd/qthrone-openflux"
cp "$ROOT/carriers/openflux/"*.go "$SOURCE/openflux/mobile/cmd/qthrone-openflux/"
(cd "$SOURCE/openflux/mobile"; go build -trimpath -buildvcs=false -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$OUT/libopenflux.so" ./cmd/qthrone-openflux)
cp "$SOURCE/openflux/LICENSE" "$OUT/OpenFlux-LICENSE"
# The fork's exit nodes frame traffic their own way: its profiles run through the fork's own bridge.
FLUXKEY=fc5647767c9daba6571ec3157f9be9b6ffe6e551
fetch_source "$SOURCE/openfluxkey" https://github.com/wlruscfd/openflux-server.git "$FLUXKEY"
mkdir -p "$SOURCE/openfluxkey/cmd/qthrone-openfluxkey"
cp "$ROOT/carriers/openfluxkey/"*.go "$SOURCE/openfluxkey/cmd/qthrone-openfluxkey/"
cp "$ROOT/carriers/openfluxkey/bridge/"*.go "$SOURCE/openfluxkey/bridge/"
(cd "$SOURCE/openfluxkey"; go build -trimpath -buildvcs=false -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$OUT/libopenfluxkey.so" ./cmd/qthrone-openfluxkey)
cp "$SOURCE/openfluxkey/LICENSE" "$OUT/OpenFluxKey-LICENSE"
for binary in "$OUT/"*.so; do
  "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$binary" | grep -q 'Type:.*DYN'
done
python3 - "$ABI" "$OUT" "$COTTENDNS" "$SOAR" "$FLUX" "$FLUXKEY" <<'PY'
import hashlib,json,subprocess,sys
from pathlib import Path
abi,out,cotten,soar,flux,fluxkey=sys.argv[1],Path(sys.argv[2]),sys.argv[3],sys.argv[4],sys.argv[5],sys.argv[6]
commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
paths=sorted(p for folder in ('carriers/cottendns','carriers/soar','carriers/openflux','carriers/openfluxkey') for p in Path(folder).rglob('*.go'))
paths.append(Path('scripts/build_dns_engines.sh'))
inputs={p.as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(out/'DNS.json').write_text(json.dumps({'build_commit':commit,'abi':abi,'inputs':inputs},indent=2)+'\n')
(out/'SOURCES.txt').write_text(f'qThroneMobile DNS engines {commit}\nCottenDNS {cotten}\nSoar {soar}\nOpenFlux {flux}\nOpenFlux Key {fluxkey}\nAndroid ABI {abi}\n')
(out/'SHA256SUMS').write_text(''.join(f'{hashlib.sha256((out/n).read_bytes()).hexdigest()}  {n}\n' for n in ('libcottendns.so','libsoar.so','libopenflux.so','libopenfluxkey.so')))
PY
tar -C "$OUT" -czf "qthrone-dns-$(git rev-parse --short=8 HEAD)-$ABI.tar.gz" \
  libcottendns.so libsoar.so libopenflux.so libopenfluxkey.so CottenDNS-LICENSE Soar-LICENSE OpenFlux-LICENSE OpenFluxKey-LICENSE \
  DNS.json SOURCES.txt SHA256SUMS
