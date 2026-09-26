#!/bin/bash
# Run as an unprivileged user on a clean Ubuntu 22.04+ desktop baseline.
# No host Java, GTK4 or download tools may supply missing AppImage payloads.
set -euo pipefail
APPIMAGE=$(realpath "${1:?Pass the AppImage to verify}")
if ldconfig -p | grep 'libgtk-4\.so' >/dev/null || command -v java >/dev/null; then
    echo 'AppImage portability must be tested without host GTK4 or Java' >&2
    exit 1
fi
for tool in aria2c yt-dlp httrack ffmpeg proxychains4 tor; do
    if command -v "$tool" >/dev/null; then
        echo "Portability test must not have host $tool" >&2
        exit 1
    fi
done
CHECK_ROOT=$(mktemp -d)
trap 'chmod -R u+w "$CHECK_ROOT"; rm -rf "$CHECK_ROOT"' EXIT
mkdir "$CHECK_ROOT/path with spaces" "$CHECK_ROOT/tmp"
cp "$APPIMAGE" "$CHECK_ROOT/app.AppImage"
chmod +x "$CHECK_ROOT/app.AppImage"
(cd "$CHECK_ROOT/path with spaces" && "$CHECK_ROOT/app.AppImage" --appimage-extract >/dev/null)
APPDIR="$CHECK_ROOT/path with spaces/squashfs-root"
test -x "$APPDIR/AppRun"
test -s "$APPDIR/usr/lib/odm/libgtk-4.so.1"
test "$(file -Lb --mime-type "$APPDIR/.DirIcon")" = image/png
test -s "$APPDIR/usr/share/metainfo/io.github.getodm.OpenDownloadManager.appdata.xml"
if find "$APPDIR" -name 'libc.so*' -o -name 'ld-linux*' | grep . >/dev/null; then
    echo 'AppImage must use the host libc and loader' >&2
    exit 1
fi
chmod -R a-w "$APPDIR"
export TMPDIR="$CHECK_ROOT/tmp"
export XDG_CONFIG_HOME="$CHECK_ROOT/config" XDG_DATA_HOME="$CHECK_ROOT/data"
export XDG_STATE_HOME="$CHECK_ROOT/state" XDG_CACHE_HOME="$CHECK_ROOT/cache"
export LANG=C.UTF-8 GTK_A11Y=none GDK_BACKEND=x11 G_DEBUG=fatal-criticals
unset LD_LIBRARY_PATH GDK_PIXBUF_MODULEDIR GDK_PIXBUF_MODULE_FILE
timeout -k 10s 150s xvfb-run -a -s '-screen 0 1280x900x24' dbus-run-session -- \
    python3 - "$APPDIR" "$CHECK_ROOT" <<'PY'
import functools
import http.server
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time

appdir, root = map(Path, sys.argv[1:])
tools = appdir / 'usr/bin'
env = dict(os.environ, PATH=f'{tools}:{os.environ["PATH"]}')

def run(tool, *args):
    result = subprocess.run([str(tools / tool), *map(str, args)], env=env,
                            capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, f'{tool}: {result.stdout}\n{result.stderr}'
    return result

# Exercise actual transfers and media processing using only bundled tools.
serve = root / 'server'
serve.mkdir()
payload = b'Open Download Manager AppImage transfer check\n' * 1024
(serve / 'file.bin').write_bytes(payload)
(serve / 'index.html').write_text('<html><body>ODM mirror check</body></html>')
run('ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i',
    'color=c=blue:s=64x64:d=1', '-c:v', 'mpeg4', serve / 'movie.mp4')
run('ffprobe', '-v', 'error', '-show_format', serve / 'movie.mp4')
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0),
    functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(serve)))
threading.Thread(target=server.serve_forever, daemon=True).start()
url = f'http://127.0.0.1:{server.server_port}'
try:
    run('aria2c', '--no-conf=true', '--dir', root, '--out=aria2.bin', url + '/file.bin')
    run('curl', '--fail', '--output', root / 'curl.bin', url + '/file.bin')
    run('yt-dlp', '--ignore-config', '--no-playlist', '-o', root / 'media.mp4', url + '/movie.mp4')
    run('httrack', url + '/index.html', '-O', root / 'mirror', '--quiet')
    assert (root / 'aria2.bin').read_bytes() == payload
    assert (root / 'curl.bin').read_bytes() == payload
    assert (root / 'media.mp4').read_bytes() == (serve / 'movie.mp4').read_bytes()
    assert any('ODM mirror check' in p.read_text() for p in (root / 'mirror').rglob('*.html'))
    run('tor', '--version')
    # A localnet route tests the relocated proxychains preload without needing
    # an external proxy or Tor network to be reachable.
    config = root / 'proxychains.conf'
    config.write_text('strict_chain\nlocalnet 127.0.0.0/255.0.0.0\n[ProxyList]\nsocks5 127.0.0.1 9\n')
    proxy_caches = set(Path('/tmp').glob('odm-proxychains.*'))
    run('proxychains4', '-f', config, tools / 'curl', '--fail', '--output',
        root / 'proxy.bin', url + '/file.bin')
    assert (root / 'proxy.bin').read_bytes() == payload
    assert set(Path('/tmp').glob('odm-proxychains.*')) == proxy_caches, 'proxychains leaked its preload cache'
finally:
    server.shutdown()
print('Bundled aria2, curl, yt-dlp, HTTrack, FFmpeg, proxychains and Tor passed')

log = root / 'startup.log'
with log.open('w') as output:
    app = subprocess.Popen([str(appdir / 'AppRun')], stdout=output,
                           stderr=subprocess.STDOUT, start_new_session=True)
    try:
        deadline = time.monotonic() + 30
        window = None
        while time.monotonic() < deadline:
            assert app.poll() is None, f'AppImage exited early: {app.returncode}'
            search = subprocess.run(['xdotool', 'search', '--onlyvisible', '--name', '^oDM$'],
                                    capture_output=True, text=True)
            if search.returncode == 0 and 'MainWindow constructed' in log.read_text():
                window = search.stdout.splitlines()[0]
                break
            time.sleep(0.5)
        assert window, 'AppImage did not display its main window within 30 seconds'
        subprocess.run(['xdotool', 'windowfocus', '--sync', window], check=True)
        subprocess.run(['xdotool', 'key', 'ctrl+n'], check=True)
        subprocess.run(['xdotool', 'type', '--clearmodifiers', 'AppImage'], check=True)
        time.sleep(1)
        subprocess.run(['xdotool', 'key', 'Escape'], check=True)
        time.sleep(30)
        assert app.poll() is None, f'AppImage crashed: {app.returncode}'
        mappings = ''
        for child in Path(f'/proc/{app.pid}/task/{app.pid}/children').read_text().split():
            mappings += Path(f'/proc/{child}/maps').read_text()
        for library in ('libgtk-4.so.1', 'libglib-2.0.so.0', 'libgobject-2.0.so.0',
                        'libgio-2.0.so.0', 'libgdk_pixbuf-2.0.so.0'):
            assert str(appdir / 'usr/lib/odm' / library) in mappings, library
        text = log.read_text()
        for error in ('Startup failed', 'NoClassDefFoundError', 'NoSuchMethodError',
                      'symbol lookup error', 'CRITICAL'):
            assert error not in text, text
        print('AppImage displayed its main window and survived 30 seconds without host GTK4, Java or download tools')
    finally:
        if app.poll() is None:
            os.killpg(app.pid, signal.SIGTERM)
        try:
            app.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(app.pid, signal.SIGKILL)
            app.wait()
        print(log.read_text())
assert not list((root / 'tmp').glob('odm-appimage.*')), 'AppRun leaked its loader cache'
PY
