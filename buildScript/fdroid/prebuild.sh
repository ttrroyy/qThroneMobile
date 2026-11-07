#!/bin/bash
set -e

# Build app/libs/RawlineCore.aar and the sing-box schema asset from the desktop repository at the ref pinned in
# nb4a.properties (gomobile + gobind on PATH, JDK 17, ANDROID_HOME/ANDROID_NDK_HOME set), the same as
# .github/actions/rawline-core.
THRONE_CORE_REF=$(sed -n 's/^THRONE_CORE_REF=//p' nb4a.properties | tr -d '\r[:space:]')
AAR_REF=$(sed -n 's/^THRONE_AAR_REF=//p' nb4a.properties | tr -d '\r[:space:]')
THRONE_CORE_REF=${AAR_REF:-$THRONE_CORE_REF}
test -n "$THRONE_CORE_REF"
CORE_DIR="$(cd .. && pwd)/Rawline"
rm -rf "$CORE_DIR"
git init -q "$CORE_DIR"
git -C "$CORE_DIR" remote add origin https://github.com/ttrroyy/qThrone
git -C "$CORE_DIR" fetch -q --depth 1 origin "$THRONE_CORE_REF"
git -C "$CORE_DIR" checkout -q --detach FETCH_HEAD

go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13
export PATH="$(go env GOPATH)/bin:$PATH"

python3 scripts/patch_mobile_warp.py "$CORE_DIR/core"

DEST="$PWD/app/libs"
(cd "$CORE_DIR" && DEST="$DEST" bash script/build_android.sh)

SCHEMA="$PWD/app/src/main/assets/schema/sing-box.json"
mkdir -p "$(dirname "$SCHEMA")"
TAGS=$(sed -n 's/^TAGS="\(.*\)"$/\1/p' "$CORE_DIR/script/build_android.sh")
test -n "$TAGS"
(cd "$CORE_DIR/core" && CGO_ENABLED=0 go run -trimpath -ldflags=-checklinkname=0 -tags "$TAGS,with_purego" ./cmd/schemagen -o "$SCHEMA")

# Bundle the throneproj/routeprofiles snapshot (same as .github/actions/routeprofiles).
ROUTES="$PWD/app/src/main/assets/routeprofiles"
rm -rf "$ROUTES"
mkdir -p "$ROUTES/profile"
curl -fsSL --retry 3 -o "$ROUTES/srslist.h" https://raw.githubusercontent.com/throneproj/routeprofiles/rule-set/srslist.h
test -s "$ROUTES/srslist.h"
curl -fsSL --retry 3 https://codeload.github.com/throneproj/routeprofiles/tar.gz/refs/heads/profile \
  | tar -xz --strip-components=1 -C "$ROUTES/profile"

# Bundle the sing-box dashboard (same as .github/actions/sing-box-dashboard).
DASHBOARD="$PWD/app/src/main/assets/sb-dashboard"
rm -rf "$DASHBOARD"
mkdir -p "$DASHBOARD"
curl -fsSL --retry 3 https://codeload.github.com/SagerNet/sing-box-dashboard/tar.gz/refs/heads/gh-pages \
  | tar -xz --strip-components=1 -C "$DASHBOARD"
find "$DASHBOARD" -type f -name '.*' -delete
test -z "$(find "$DASHBOARD" -type d -name '_*')"
test -s "$DASHBOARD/index.html"

# F-Droid builds stable tags: the same versionCode as the GitHub release (VERSION_CODE * 1000 + 999).
grep -q '^rawline.build=' gradle.properties || printf '\nrawline.build=999\n' >> gradle.properties
