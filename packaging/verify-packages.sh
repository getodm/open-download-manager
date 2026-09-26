#!/bin/bash
# Validate the artifacts that will be attached to a release. Run in odm-dev.
set -euo pipefail
PACKAGE_ROOT="$(cd "$(dirname "$0")" && pwd)"
DIST="$PACKAGE_ROOT/dist"
VERSION="${1:?Pass the package version}"
[[ "$VERSION" =~ ^[0-9]+([.][0-9]+){1,3}$ ]] || exit 2
CHECK_ROOT="$(mktemp -d)"
trap 'rm -rf "$CHECK_ROOT"' EXIT
DEB="$DIST/open-download-manager_${VERSION}_amd64.deb"
RPM="$DIST/open-download-manager-${VERSION}-1.x86_64.rpm"
ARCH="$DIST/open-download-manager-${VERSION}-1-x86_64.pkg.tar.zst"
APPIMAGE="$DIST/Open_Download_Manager-${VERSION}-x86_64.AppImage"
for artifact in "$DEB" "$RPM" "$ARCH" "$APPIMAGE" "$APPIMAGE.zsync"; do test -s "$artifact"; done
[[ "$(dpkg-deb -f "$DEB" Version)" == "$VERSION" ]]
[[ "$(dpkg-deb -f "$DEB" Architecture)" == amd64 ]]
[[ "$(rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --qf '%{VERSION}-%{RELEASE}' "$RPM")" == "$VERSION-1" ]]
[[ "$(rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --qf '%{ARCH}' "$RPM")" == x86_64 ]]
rpm --dbpath "$CHECK_ROOT/rpmdb" -K --nosignature "$RPM"
rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --qf '[%{FILEUSERNAME}:%{FILEGROUPNAME}\n]' "$RPM" > "$CHECK_ROOT/rpm-owners"
if grep -vqx 'root:root' "$CHECK_ROOT/rpm-owners"; then
    echo 'RPM contains non-root payload ownership' >&2; exit 1
fi
mkdir "$CHECK_ROOT/deb" "$CHECK_ROOT/rpm" "$CHECK_ROOT/arch"
dpkg-deb --fsys-tarfile "$DEB" > "$CHECK_ROOT/deb.tar"
zstd -dq "$ARCH" -o "$CHECK_ROOT/arch.tar"
python3 - "$CHECK_ROOT/deb.tar" "$CHECK_ROOT/arch.tar" <<'PY'
import sys, tarfile
for archive in sys.argv[1:]:
    with tarfile.open(archive) as package:
        assert all(m.uid == 0 and m.gid == 0 for m in package.getmembers()), archive
PY
tar -xf "$CHECK_ROOT/deb.tar" -C "$CHECK_ROOT/deb"
rpm2cpio "$RPM" | bsdtar -xf - -C "$CHECK_ROOT/rpm"
tar -xf "$CHECK_ROOT/arch.tar" -C "$CHECK_ROOT/arch"
grep -qx "pkgver = ${VERSION}-1" "$CHECK_ROOT/arch/.PKGINFO"
grep -qx 'arch = x86_64' "$CHECK_ROOT/arch/.PKGINFO"
test -s "$CHECK_ROOT/arch/.MTREE"
test -s "$CHECK_ROOT/arch/.BUILDINFO"
python3 - "$CHECK_ROOT/arch" <<'PYMTREE'
import gzip, hashlib, pathlib, re, shlex, sys
root = pathlib.Path(sys.argv[1])
checked = 0
for line in gzip.open(root / '.MTREE', 'rt'):
    if not line.startswith('./'):
        continue
    fields = shlex.split(line)
    digest = next((v.split('=', 1)[1] for v in fields[1:] if v.startswith('sha256digest=')), None)
    if digest:
        path = root / fields[0]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, str(path)
        checked += 1
assert checked > 0, 'MTREE contains no payload hashes'
print(f'Validated {checked} Arch MTREE hashes')
PYMTREE
# Each format must carry precisely the same application, runtime and licensing files.
for format in deb rpm arch; do
    root="$CHECK_ROOT/$format"
    test -x "$root/usr/bin/open-download-manager"
    test -s "$root/usr/share/doc/open-download-manager/copyright"
    test -s "$root/usr/share/licenses/open-download-manager/LICENSE"
    test -s "$root/usr/share/applications/org.odm.desktop"
    grep -qx 'Exec=open-download-manager %U' "$root/usr/share/applications/org.odm.desktop"
    grep -qx 'MimeType=x-scheme-handler/magnet;' "$root/usr/share/applications/org.odm.desktop"
    grep -qx 'Icon=open-download-manager' "$root/usr/share/applications/org.odm.desktop"
    grep -qx 'StartupWMClass=org.odm' "$root/usr/share/applications/org.odm.desktop"
    test -s "$root/usr/share/icons/hicolor/scalable/apps/open-download-manager.svg"
    cmp "$PACKAGE_ROOT/../odm-gtk4/src/main/resources/icons/hicolor/16x16/apps/open-download-manager.svg" \
        "$root/usr/share/icons/hicolor/16x16/apps/open-download-manager.svg"
    for size in 16 24 32 48 64 128 256 512; do
        icon="icons/hicolor/${size}x${size}/apps/open-download-manager.png"
        cmp "$PACKAGE_ROOT/../odm-gtk4/src/main/resources/$icon" "$root/usr/share/$icon"
    done
    (cd "$root" && find opt usr -type f -print0 | sort -z | xargs -0 sha256sum) > "$CHECK_ROOT/$format.sha256"
done
diff -u "$CHECK_ROOT/deb.sha256" "$CHECK_ROOT/rpm.sha256"
diff -u "$CHECK_ROOT/deb.sha256" "$CHECK_ROOT/arch.sha256"
# AppImage must contain the same application/runtime plus its native closure.
mkdir "$CHECK_ROOT/appimage"
(cd "$CHECK_ROOT/appimage" && "$APPIMAGE" --appimage-extract >/dev/null)
APPDIR="$CHECK_ROOT/appimage/squashfs-root"
test -x "$APPDIR/AppRun"
(cd "$APPDIR" && sha256sum -c "$CHECK_ROOT/deb.sha256" >/dev/null)
desktop-file-validate "$APPDIR/io.github.getodm.OpenDownloadManager.desktop"
env -u LD_LIBRARY_PATH appstreamcli validate --no-net "$APPDIR/usr/share/metainfo/io.github.getodm.OpenDownloadManager.appdata.xml"
env -u LD_LIBRARY_PATH bash /opt/odm-appimage/appdir-lint.sh "$APPDIR"
test "$(file -Lb --mime-type "$APPDIR/.DirIcon")" = image/png
for tool in aria2c curl yt-dlp httrack ffmpeg ffprobe proxychains4 tor; do
    test -x "$APPDIR/usr/bin/$tool"
done
test -s "$APPDIR/usr/lib/odm/libgtk-4.so.1"
if find "$APPDIR" -name 'libc.so*' -o -name 'ld-linux*' | grep . >/dev/null; then
    echo 'AppImage contains the host C library or loader' >&2; exit 1
fi
APP_ROOT="$CHECK_ROOT/deb/opt/open-download-manager"
python3 - "$APP_ROOT/odm.jar" <<'PYNATIVES'
import sys, zipfile
def require_amd64_elf(jar, resource):
    with jar.open(resource) as binary:
        header = binary.read(20)
    assert header[:6] == b'\x7fELF\x02\x01' and int.from_bytes(header[18:20], 'little') == 62, \
        f'Packaged native resource must be Linux x86-64 ELF: {resource}'

with zipfile.ZipFile(sys.argv[1]) as jar:
    names = set(jar.namelist())
    for required in ('driver/linux/node', 'driver/linux/LICENSE', 'driver/package/cli.js'):
        assert required in names, f'Missing packaged Playwright resource: {required}'
    unexpected = sorted(name for name in names if name.startswith('driver/')
                        and not name.endswith('/')
                        and not name.startswith(('driver/linux/', 'driver/package/')))
    assert not unexpected, f'Unexpected Playwright platform resources: {unexpected}'
    require_amd64_elf(jar, 'driver/linux/node')
    sqlite_library = 'org/sqlite/native/Linux/x86_64/libsqlitejdbc.so'
    sqlite_natives = {name for name in names if name.startswith('org/sqlite/native/')
                      and not name.endswith('/')}
    assert sqlite_natives == {sqlite_library}, \
        f'SQLite must contain only the Linux amd64 native library: {sorted(sqlite_natives)}'
    require_amd64_elf(jar, sqlite_library)
    for resource in ('sqlite-jdbc.properties', 'META-INF/services/java.sql.Driver',
                     'META-INF/maven/org.xerial/sqlite-jdbc/LICENSE',
                     'META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus'):
        assert resource in names, f'Missing SQLite configuration or license: {resource}'
print('Playwright and SQLite contain only Linux amd64 native libraries')
PYNATIVES
"$APP_ROOT/runtime/bin/java" --list-modules > "$CHECK_ROOT/modules"
grep -q '^java.net.http@' "$CHECK_ROOT/modules"
grep -q '^jdk.localedata@' "$CHECK_ROOT/modules"
# Exercise native GTK resource loading, HTTP and SQLite with the actual bundled JVM/JAR.
cat > "$CHECK_ROOT/PackageRuntimeCheck.java" <<'JAVA'
import java.sql.DriverManager;
import java.net.http.HttpClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sun.net.httpserver.HttpServer;
import org.gnome.gtk.Gtk;
import org.odm.gtk4.UiLoader;
import org.odm.gtk4.I18n;
import org.ytdlp.BrowserMediaProbe;
import org.ytdlp.YtDlpSettings;
public class PackageRuntimeCheck {
    public static void main(String[] args) throws Exception {
        I18n.initialize();
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        if (!org.gnome.glib.MainContext.default_().acquire()) {
            throw new AssertionError("Could not own the GTK context for this runtime probe");
        }
        if (args.length > 0 && args[0].equals("fr")) {
            if (!"Préférences".equals(I18n.tr("Preferences"))
                    || !"2 éléments".equals(I18n.plural("%d item", "%d items", 2))
                    || !"1,5".equals(java.text.NumberFormat.getNumberInstance(java.util.Locale.FRANCE).format(1.5))) {
                throw new AssertionError("Packaged French catalog or Java locale data is missing");
            }
            Gtk.init();
            var builder = UiLoader.load("/ui/settings.ui");
            var window = (org.gnome.gtk.Window) builder.getObject("settings_dialog");
            if (!"Open Download Manager - Paramètres".equals(window.getTitle())) {
                throw new AssertionError("Packaged GtkBuilder did not translate the settings window");
            }
            window.destroy();
            System.out.println("Packaged French GTK, plurals and number formatting passed");
            System.exit(0);
        }
        if (!"amd64".equals(System.getProperty("os.arch"))) {
            throw new AssertionError("Bundled JVM must target amd64");
        }
        try (var http = HttpClient.newHttpClient()) { }
        // Exercise JDBC service discovery and native SQL execution from the shaded JAR.
        try (var database = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var statement = database.createStatement()) {
                statement.executeUpdate("CREATE TABLE package_check (value TEXT NOT NULL)");
                statement.executeUpdate("INSERT INTO package_check VALUES ('amd64')");
                try (var rows = statement.executeQuery("SELECT value FROM package_check")) {
                    if (!rows.next() || !"amd64".equals(rows.getString(1))) {
                        throw new AssertionError("Packaged SQLite read/write check failed");
                    }
                }
            }
        }
        Gtk.init();
        for (String ui : new String[]{"main-window", "new-download", "new-media", "new-website", "settings"}) {
            UiLoader.load("/ui/" + ui + ".ui");
        }
        System.out.println("Packaged runtime, GTK resources, HTTP and SQLite passed");
        verifyMediaProbe();
    }

    private static void verifyMediaProbe() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mediaRequested = new AtomicBoolean();
        server.createContext("/page", exchange -> {
            byte[] page = ("<video id='player'></video><script>"
                    + "document.getElementById('player').src='/movie.mp4';</script>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, page.length);
            try (var body = exchange.getResponseBody()) { body.write(page); }
        });
        server.createContext("/movie.mp4", exchange -> {
            mediaRequested.set(true);
            byte[] media = new byte[32];
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, media.length);
            try (var body = exchange.getResponseBody()) { body.write(media); }
        });
        server.start();
        try (var probe = new BrowserMediaProbe()) {
            URI page = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/page");
            var candidates = probe.probe(page, new YtDlpSettings(), null).get(35, TimeUnit.SECONDS);
            if (!mediaRequested.get() || candidates.stream()
                    .noneMatch(candidate -> candidate.url().equals(page.resolve("/movie.mp4").toString()))) {
                throw new AssertionError("Packaged browser probe did not discover the scripted media URL");
            }
        } finally {
            server.stop(0);
        }
        System.out.println("Packaged Playwright driver and browser media probing passed");
    }
}
JAVA
javac --release 25 -cp "$APP_ROOT/odm.jar" "$CHECK_ROOT/PackageRuntimeCheck.java"
xvfb-run -a "$APP_ROOT/runtime/bin/java" --enable-native-access=ALL-UNNAMED \
    -cp "$CHECK_ROOT:$APP_ROOT/odm.jar" PackageRuntimeCheck
LC_ALL=fr_FR.UTF-8 LANGUAGE=fr xvfb-run -a "$APP_ROOT/runtime/bin/java" --enable-native-access=ALL-UNNAMED \
    -cp "$CHECK_ROOT:$APP_ROOT/odm.jar" PackageRuntimeCheck fr
# Bound the real application's startup; its GTK entry point has no dedicated smoke switch.
set +e
LC_ALL=fr_FR.UTF-8 LANGUAGE=fr \
XDG_CONFIG_HOME="$CHECK_ROOT/config" XDG_DATA_HOME="$CHECK_ROOT/data" XDG_STATE_HOME="$CHECK_ROOT/state" \
    xvfb-run -a timeout -k 10s 25s "$APP_ROOT/runtime/bin/java" --enable-native-access=ALL-UNNAMED \
    -jar "$APP_ROOT/odm.jar" > "$CHECK_ROOT/launcher.log" 2>&1
launch_status=$?
set -e
cat "$CHECK_ROOT/launcher.log"
[[ "$launch_status" == 124 ]]
grep -q 'MainWindow constructed' "$CHECK_ROOT/launcher.log"
if grep -Eq 'Startup failed|NoClassDefFoundError|NoSuchMethodError' "$CHECK_ROOT/launcher.log"; then
    exit 1
fi
echo 'All four package formats and the bundled launcher passed'
