#!/usr/bin/env python3
"""Copy GTK and the download tools' native dependency closure into the staged AppDir."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def run(*args):
    return subprocess.check_output(args, text=True)


appdir = Path(sys.argv[1]).resolve()
prefix = Path(run("pkg-config", "--variable=prefix", "gtk4").strip())
subprocess.run(["pkg-config", "--atleast-version=4.14.5", "gtk4"], check=True)
libdir = appdir / "usr/lib/odm"
libdir.mkdir(parents=True, exist_ok=True)
licenses = appdir / "usr/share/doc/open-download-manager/native-libraries"
licenses.mkdir(parents=True, exist_ok=True)

# These belong to the host C/C++ runtime, display and font stack. Follow
# AppImage/AppImages' excludelist so newer host graphics drivers cannot load
# older X11/Wayland/font libraries from our GTK closure. Match versioned
# SONAMEs as well as unversioned names; never copy libc.so.6.
excluded = {
    line.split("#", 1)[0].strip()
    for line in Path("/opt/odm-appimage/excludelist").read_text().splitlines()
    if line.split("#", 1)[0].strip()
}
search = [prefix / "lib", Path("/usr/lib/x86_64-linux-gnu")]


def resolve(name):
    for directory in search:
        candidate = directory / name
        if candidate.is_file():
            return candidate
    raise RuntimeError(f"Required native library is missing: {name}")


copied = set()
origins = {}
packages = set()


def bundle(source, destination=None):
    source = Path(source)
    name = source.name
    if name in excluded:
        return
    if name in copied:
        if origins[name] != source.resolve():
            raise RuntimeError(f"Conflicting native library {name}: {source} and {origins[name]}")
        return
    origins[name] = source.resolve()
    copied.add(name)
    output = run("ldd", str(source))
    if "not found" in output:
        raise RuntimeError(f"Unresolved dependencies for {source}:\n{output}")
    target = destination or libdir / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source.resolve(), target)
    # Relative RUNPATHs keep bundled libraries private to the application and
    # its tools. Host programs must not inherit an AppImage LD_LIBRARY_PATH.
    rpath = os.path.relpath(libdir, target.parent)
    subprocess.run(["patchelf", "--set-rpath", f"$ORIGIN/{rpath}", str(target)], check=True)
    for line in output.splitlines():
        match = re.match(r"\s*\S+\s+=>\s+(/\S+)\s+\(", line)
        if match:
            bundle(match[1])
    if not source.is_relative_to(prefix):
        # Carry the distro's copyright/source notices alongside its libraries.
        for candidate in (source, source.resolve()):
            result = subprocess.run(["dpkg-query", "-S", str(candidate)], text=True,
                                    capture_output=True)
            if result.returncode == 0:
                packages.add(result.stdout.split(": ", 1)[0].split(":", 1)[0])
                break


# Bundle the tools themselves, then recursively collect their shared libraries.
# yt-dlp is a self-extracting PyInstaller binary; patchelf would invalidate
# its appended archive, so copy it unchanged.
for name in ("aria2c", "curl", "httrack", "ffmpeg", "ffprobe", "tor"):
    source = Path(shutil.which(name) or "")
    if not source.is_file():
        raise RuntimeError(f"Required download tool is missing: {name}")
    bundle(source, appdir / "usr/bin" / name)
ytdlp = appdir / "usr/bin/yt-dlp"
shutil.copy2("/usr/local/bin/yt-dlp", ytdlp)
shutil.copy2("/opt/odm-appimage/yt-dlp-LICENSE", licenses / "yt-dlp-LICENSE")
# proxychains needs a wrapper because LD_PRELOAD cannot represent spaces.
bundle(Path(shutil.which("proxychains4")), appdir / "usr/libexec/proxychains4")
shutil.copy2(Path(__file__).with_name("proxychains4"), appdir / "usr/bin/proxychains4")
(appdir / "usr/bin/proxychains").symlink_to("proxychains4")
bundle(resolve("libproxychains.so.4"))
# The standalone Python runtime may dlopen this compatibility library.
bundle(resolve("libcrypt.so.1"))
for plugin in Path("/usr/lib/x86_64-linux-gnu").glob("libhts*.so.*"):
    bundle(plugin)

for name in ("libgtk-4.so.1", "libglib-2.0.so.0", "libgobject-2.0.so.0",
             "libgio-2.0.so.0", "libgmodule-2.0.so.0", "libgdk_pixbuf-2.0.so.0",
             "libpangocairo-1.0.so.0", "libcairo-gobject.so.2",
             "libharfbuzz-gobject.so.0"):
    bundle(resolve(name))

# GdkPixbuf dlopens image loaders; ldd on GTK alone cannot discover them.
pixbuf_dir = Path(run("pkg-config", "--variable=gdk_pixbuf_moduledir", "gdk-pixbuf-2.0").strip())
for loader in sorted(pixbuf_dir.glob("*.so")):
    bundle(loader, libdir / "gdk-pixbuf-loaders" / loader.name)
query = Path(run("pkg-config", "--variable=gdk_pixbuf_query_loaders", "gdk-pixbuf-2.0").strip())
if not query.is_file() or not (libdir / "gdk-pixbuf-loaders/libpixbufloader-svg.so").is_file():
    raise RuntimeError("GdkPixbuf loader tools or the SVG loader are missing")
bundle(query, appdir / "usr/libexec/gdk-pixbuf-query-loaders")

share = appdir / "usr/share"
schemas = share / "glib-2.0/schemas"
schemas.mkdir(parents=True, exist_ok=True)
for schema in (prefix / "share/glib-2.0/schemas").glob("*.xml"):
    shutil.copy2(schema, schemas / schema.name)
subprocess.run([str(prefix / "bin/glib-compile-schemas"), str(schemas)], check=True)
for name in ("icons/Adwaita", "mime"):
    shutil.copytree(Path("/usr/share") / name, share / name, symlinks=False, dirs_exist_ok=True)
shutil.copytree(prefix / "share/locale", share / "locale", dirs_exist_ok=True)
shutil.copytree(prefix / "share/doc/odm-gtk", licenses / "odm-gtk", dirs_exist_ok=True)
for package in sorted(packages):
    copyright_file = Path("/usr/share/doc") / package / "copyright"
    if copyright_file.is_file():
        shutil.copy2(copyright_file, licenses / f"{package}-copyright")
(licenses / "packages.txt").write_text(run("dpkg-query", "-W", "-f=${Package} ${Version}\n", *sorted(packages)))
# Enforce the catalog's Ubuntu 22.04 ABI floor for all bundled ELF payloads.
for path in appdir.rglob("*"):
    if not path.is_file() or path.is_symlink():
        continue
    with path.open("rb") as binary:
        if binary.read(4) != b"\x7fELF":
            continue
    versions = re.findall(r"@GLIBC_(\d+)\.(\d+)", run("readelf", "--dyn-syms", "--wide", str(path)))
    if any(tuple(map(int, version)) > (2, 35) for version in versions):
        raise RuntimeError(f"Native payload exceeds the glibc 2.35 floor: {path}")
print(f"Bundled {len(copied)} native libraries/tools with GTK {run('pkg-config', '--modversion', 'gtk4').strip()}")
