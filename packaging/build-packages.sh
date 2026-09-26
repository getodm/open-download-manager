#!/bin/bash
# Package builder for Open Download Manager (GTK4).
# Builds the shaded jar + a self-contained jlink runtime, then assembles
# .deb / .rpm / .pkg.tar.zst / AppImage artifacts. Run inside odm-dev.
set -euo pipefail

# Package metadata and the shaded native libraries target Linux amd64.
if [[ "$(uname -s):$(uname -m)" != "Linux:x86_64" ]]; then
    echo "Packaging requires Linux amd64 (x86_64); use an amd64 Docker environment." >&2
    exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PROJECT_VERSION="$(mvn -q -N help:evaluate -Dexpression=project.version -DforceStdout)"
VERSION="${1:-$PROJECT_VERSION}"
if [[ ! "$VERSION" =~ ^[0-9]+([.][0-9]+){1,3}$ ]]; then
    echo "Invalid package version: expected numeric dotted version" >&2
    exit 2
fi
if [[ "$VERSION" != "$PROJECT_VERSION" ]]; then
    echo "Package version $VERSION must match Maven version $PROJECT_VERSION" >&2
    exit 2
fi
STAGE="$ROOT/packaging/stage"
DIST="$ROOT/packaging/dist"
RUNTIME="$STAGE/opt/open-download-manager/runtime"
APP="$STAGE/opt/open-download-manager"
JAR="$ROOT/odm-gtk4/target/odm-gtk4-${PROJECT_VERSION}-jar-with-dependencies.jar"

log() { echo "[odm-package] $*"; }

# Modules from jdeps over the shaded jar (+ crypto/naming/management for
# TLS, WebSocket client usage, and runtime introspection). Playwright extracts
# its driver through zipfs and Gson uses Unsafe for browser protocol objects;
# these dynamically loaded modules are not reported by jdeps.
JDK_MODULES="java.base,java.desktop,java.sql,java.logging,java.net.http,jdk.httpserver,jdk.crypto.ec,jdk.zipfs,jdk.unsupported,jdk.localedata,java.naming,java.management"

log "Building shaded jar..."
# Packaging deliberately skips tests, so it must also skip JaCoCo's test
# coverage gate. Otherwise stale jacoco.exec data from an earlier test run can
# make a release build fail even though no tests execute here.
mvn -q -pl odm-gtk4 -am package -DskipTests -Djacoco.skip=true

log "Assembling application tree under $STAGE..."
rm -rf "$STAGE"
mkdir -p "$DIST" "$APP" "$RUNTIME" "$STAGE/usr/bin" \
    "$STAGE/usr/share/applications" \
    "$STAGE/usr/share/metainfo" \
    "$STAGE/usr/share/doc/open-download-manager" \
    "$STAGE/usr/share/licenses/open-download-manager" \
    "$STAGE/usr/share/icons/hicolor"

cp "$JAR" "$APP/odm.jar"
cp LICENSE "$STAGE/usr/share/licenses/open-download-manager/LICENSE"
cp LICENSE "$STAGE/usr/share/doc/open-download-manager/copyright"

log "Creating jlink runtime (modules: $JDK_MODULES)..."
rm -rf "$RUNTIME"
jlink --add-modules "$JDK_MODULES" \
    --strip-debug --no-header-files --no-man-pages --compress zip-6 \
    --output "$RUNTIME"
runtime_modules=$("$RUNTIME/bin/java" --list-modules | cut -d@ -f1)
grep -qx 'java.net.http' <<<"$runtime_modules"
required_modules=$(jdeps --ignore-missing-deps --multi-release 25 \
    --print-module-deps "$APP/odm.jar")
for module in ${required_modules//,/ }; do
    if ! grep -qx "$module" <<<"$runtime_modules"; then
        echo "Bundled runtime is missing required module: $module" >&2
        exit 1
    fi
done

cat > "$STAGE/usr/bin/open-download-manager" <<'EOF'
#!/bin/sh
# oDM launcher: prefer the bundled runtime, fall back to system java
APP_HOME=/opt/open-download-manager
if [ -x "$APP_HOME/runtime/bin/java" ]; then
    exec "$APP_HOME/runtime/bin/java" --enable-native-access=ALL-UNNAMED -jar "$APP_HOME/odm.jar" "$@"
else
    exec java --enable-native-access=ALL-UNNAMED -jar "$APP_HOME/odm.jar" "$@"
fi
EOF
chmod 755 "$STAGE/usr/bin/open-download-manager"

cp packaging/resources/open-download-manager.desktop "$STAGE/usr/share/applications/org.odm.desktop"
cp packaging/resources/io.github.getodm.OpenDownloadManager.appdata.xml "$STAGE/usr/share/metainfo/"
cp -a odm-gtk4/src/main/resources/icons/hicolor/. "$STAGE/usr/share/icons/hicolor/"

log "Stage complete:"
du -sh "$STAGE" "$RUNTIME"

# ---- .deb ----
build_deb() {
    log "Building .deb..."
    local debroot="$STAGE-deb"
    rm -rf "$debroot"
    cp -r "$STAGE" "$debroot"
    mkdir -p "$debroot/DEBIAN"
    cp packaging/debian/control "$debroot/DEBIAN/control"
    cp packaging/debian/postinst "$debroot/DEBIAN/postinst" 2>/dev/null || true
    cp packaging/debian/prerm "$debroot/DEBIAN/prerm" 2>/dev/null || true
    [ -f "$debroot/DEBIAN/postinst" ] && chmod 755 "$debroot/DEBIAN/postinst"
    [ -f "$debroot/DEBIAN/prerm" ] && chmod 755 "$debroot/DEBIAN/prerm"
    sed -i "s/__VERSION__/${VERSION}/g" "$debroot/DEBIAN/control"
    dpkg-deb --root-owner-group --build -Zxz "$debroot" \
        "$DIST/open-download-manager_${VERSION}_amd64.deb"
    log "Built dist/open-download-manager_${VERSION}_amd64.deb"
}

# ---- .rpm ----
build_rpm() {
    log "Building .rpm..."
    command -v rpmbuild >/dev/null || { log "rpmbuild not found, skipping rpm"; return 0; }
    local rpmtop="$ROOT/packaging/rpmbuild"
    rm -rf "$rpmtop"
    mkdir -p "$rpmtop"/{BUILD,RPMS,SOURCES,SPECS,SRPMS}
    sed -e "s/__VERSION__/${VERSION}/g" packaging/rpm/open-download-manager.spec \
        > "$rpmtop/SPECS/open-download-manager.spec"
    (cd "$rpmtop" && rpmbuild --define "_topdir $rpmtop" --define "stage $STAGE" \
        --nodeps --nocheck -bb "$rpmtop/SPECS/open-download-manager.spec")
    find "$rpmtop/RPMS" "$ROOT/rpmbuild/RPMS" -name "*.rpm" -exec mv {} "$DIST/" \; 2>/dev/null || true
    rm -rf "$rpmtop" "$ROOT/rpmbuild"
}

# ---- Arch .pkg.tar.zst ----
build_arch() {
    log "Building .pkg.tar.zst..."
    local archroot="$ROOT/packaging/archbuild"
    rm -rf "$archroot"
    mkdir -p "$archroot/pkg"
    cp -a "$STAGE/." "$archroot/pkg/"
    chmod 755 "$archroot/pkg/usr/bin/open-download-manager"

    # Minimal pacman package: .PKGINFO + payload, compressed with zstd
    local size
    size=$(du -sk "$archroot/pkg" | cut -f1)
    local builddate
    builddate=$(date +%s)
    cat > "$archroot/pkg/.PKGINFO" <<EOF
pkgname = open-download-manager
pkgbase = open-download-manager
pkgver = ${VERSION}-1
pkgdesc = Full-featured download manager for Linux based on aria2, yt-dlp and httrack (bundled Java runtime)
url = https://github.com/albilu/odm
arch = x86_64
license = GPL-3.0-or-later
depend = gtk4>=4.10
depend = aria2>=1.34.0
depend = curl>=7.80.0
depend = yt-dlp>=2024.01.01
depend = httrack>=3.49.0
optdepend = proxychains-ng: SOCKS proxy chains
optdepend = tor: anonymous downloads
optdepend = ffmpeg: video processing
optdepend = python-subliminal: generic subtitle downloads
optdepend = clamav: completion-time malware scanning
optdepend = chromium: headless media discovery when page extraction fails
packager = ODM Development Team <dev@odm-project.org>
size = $((size * 1024))
builddate = ${builddate}
EOF
    cat > "$archroot/pkg/.BUILDINFO" <<EOF
format = 2
pkgname = open-download-manager
pkgbase = open-download-manager
pkgver = ${VERSION}-1
pkgarch = x86_64
pkgbuild_sha256sum = $(sha256sum packaging/arch/PKGBUILD | cut -d' ' -f1)
packager = ODM Development Team <dev@odm-project.org>
builddate = ${builddate}
builddir = /app
startdir = /app
buildtool = odm-package-builder
buildtoolver = 1.0.0
buildenv = !distcc
buildenv = color
buildenv = !ccache
buildenv = check
buildenv = !sign
options = strip
options = docs
options = !libtool
options = !staticlibs
options = emptydirs
options = zipman
options = purge
options = !debug
options = lto
EOF
    (cd "$archroot/pkg" && LANG=C bsdtar -czf .MTREE --format=mtree \
        --uid 0 --gid 0 \
        --options='!all,use-set,type,uid,gid,mode,time,size,sha256,link' \
        .PKGINFO .BUILDINFO opt usr)
    (cd "$archroot/pkg" && tar -C "$archroot/pkg" \
        --owner=0 --group=0 --numeric-owner \
        --use-compress-program="zstd -19 -T0" \
        -cf "$DIST/open-download-manager-${VERSION}-1-x86_64.pkg.tar.zst" \
        .PKGINFO .BUILDINFO .MTREE opt usr)
    rm -rf "$archroot"
    log "Built dist/open-download-manager-${VERSION}-1-x86_64.pkg.tar.zst"
}

# AppImage extends the same payload with GTK and the download tools.
build_appimage() {
    log "Building AppImage..."
    local appdir="$ROOT/packaging/target/OpenDownloadManager.AppDir"
    rm -rf "$appdir"
    mkdir -p "$appdir"
    cp -a "$STAGE/." "$appdir/"
    python3 packaging/appimage/bundle-native.py "$appdir"
    install -m 755 packaging/appimage/AppRun "$appdir/AppRun"
    cp "$STAGE/usr/share/applications/org.odm.desktop" "$appdir/io.github.getodm.OpenDownloadManager.desktop"
    cp "$STAGE/usr/share/icons/hicolor/512x512/apps/open-download-manager.png" "$appdir/"
    ln -s open-download-manager.png "$appdir/.DirIcon"
    desktop-file-validate "$appdir/io.github.getodm.OpenDownloadManager.desktop"
    env -u LD_LIBRARY_PATH appstreamcli validate --no-net "$appdir/usr/share/metainfo/io.github.getodm.OpenDownloadManager.appdata.xml"
    env -u LD_LIBRARY_PATH bash /opt/odm-appimage/appdir-lint.sh "$appdir"
    # appimagetool writes the zsync sidecar into its working directory.
    (
        cd "$DIST"
        ARCH=x86_64 /opt/odm-appimage/appimagetool --appimage-extract-and-run \
            --runtime-file /opt/odm-appimage/runtime-x86_64 \
            --updateinformation 'gh-releases-zsync|getodm|open-download-manager|latest|Open_Download_Manager-*-x86_64.AppImage.zsync' \
            "$appdir" "Open_Download_Manager-${VERSION}-x86_64.AppImage"
    )
    rm -rf "$appdir"
}

build_deb
build_rpm
build_arch
build_appimage

log "Artifacts:"
ls -la "$DIST/"*.deb "$DIST/"*.rpm "$DIST/"*.pkg.tar.zst "$DIST/"*.AppImage*
log "Done."
