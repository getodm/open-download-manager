# The catalog tests on Ubuntu 22.04; every bundled native library must run
# with its glibc 2.35. Build the required GTK version on that baseline.
FROM eclipse-temurin:25-jdk-jammy

# Avoid interactive prompts
ENV DEBIAN_FRONTEND=noninteractive

# Install all dependencies
RUN apt-get update && apt-get install -y \
    # Maven (JDK 25 comes from the base image)
    maven \
    # Build tools
    git \
    gettext \
    locales \
    curl \
    wget \
    build-essential \
    ninja-build \
    python3-pip \
    python3-packaging \
    libpcre2-dev \
    libffi-dev \
    libmount-dev \
    libxml2-dev \
    libdrm-dev \
    libtiff-dev \
    # GTK libraries for java-gi (GTK4)
    libgtk-4-dev \
    libglib2.0-dev \
    pkg-config \
    # External dependencies (matching debian/control versions)
    aria2 \
    httrack \
    proxychains4 \
    tor \
    ffmpeg \
    subliminal \
    # X11 for GUI testing
    xvfb \
    x11-utils \
    dbus-x11 \
    xauth \
    xdotool \
    # Package building tools
    dpkg-dev \
    fakeroot \
    rpm \
    file \
    zstd \
    zsync \
    libarchive-tools \
    patchelf \
    desktop-file-utils \
    appstream \
    libfile-mimeinfo-perl \
    # Utilities
    vim \
    tree \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m pip install --no-cache-dir meson==1.4.2
COPY packaging/appimage/build-gtk.sh /tmp/odm-build-gtk.sh
RUN bash /tmp/odm-build-gtk.sh && rm /tmp/odm-build-gtk.sh
ENV PKG_CONFIG_PATH=/opt/odm-gtk/lib/pkgconfig:/opt/odm-gtk/share/pkgconfig
ENV LD_LIBRARY_PATH=/opt/odm-gtk/lib
ENV XDG_DATA_DIRS=/opt/odm-gtk/share:/usr/local/share:/usr/share

# Pin upstream binaries and check their published SHA256 digests. Reuse the
# verified appimagetool's own type-2 runtime instead of a moving runtime URL.
RUN mkdir -p /opt/odm-appimage && \
    curl -fL --retry 3 https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage -o /opt/odm-appimage/appimagetool && \
    echo 'ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0  /opt/odm-appimage/appimagetool' | sha256sum -c - && \
    chmod 755 /opt/odm-appimage/appimagetool && \
    head -c "$(/opt/odm-appimage/appimagetool --appimage-offset)" /opt/odm-appimage/appimagetool > /opt/odm-appimage/runtime-x86_64 && \
    curl -fL --retry 3 https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp_linux -o /usr/local/bin/yt-dlp && \
    echo '58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a  /usr/local/bin/yt-dlp' | sha256sum -c - && \
    chmod 755 /usr/local/bin/yt-dlp && \
    curl -fL --retry 3 https://raw.githubusercontent.com/yt-dlp/yt-dlp/2026.08.19/LICENSE -o /opt/odm-appimage/yt-dlp-LICENSE && \
    for name in excludelist appdir-lint.sh; do \
        curl -fL --retry 3 "https://raw.githubusercontent.com/AppImage/AppImages/19e30b276ffedf4d3b4b56bc6320f463625a74f8/$name" -o "/opt/odm-appimage/$name"; \
    done

# Exercise gettext with installed desktop locales, including French regional fallback.
RUN localedef -i en_US -f UTF-8 en_US.UTF-8 && \
    localedef -i fr_FR -f UTF-8 fr_FR.UTF-8 && \
    localedef -i fr_BE -f UTF-8 fr_BE.UTF-8 && \
    localedef -i fr_CA -f UTF-8 fr_CA.UTF-8 && \
    localedef -i de_DE -f UTF-8 de_DE.UTF-8

# Install the matching Chromium build for headless media discovery. Keep the
# browser available to the non-root development user; probes never download it.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/odm-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY pom.xml /tmp/odm-browser/pom.xml
COPY core/pom.xml /tmp/odm-browser/core/pom.xml
RUN mvn -q -f /tmp/odm-browser/core/pom.xml exec:java \
        -Dexec.mainClass=com.microsoft.playwright.CLI \
        -Dexec.args="install --with-deps --no-shell chromium" \
        -Dexec.classpathScope=runtime && \
    chmod -R a+rX /opt/odm-playwright && \
    rm -rf /tmp/odm-browser /root/.m2 /var/lib/apt/lists/*

# JAVA_HOME is already set by the temurin base image

# Match the checkout owner, including CI runners whose UID is not 1000.
ARG ODM_UID=1000
ARG ODM_GID=1000
RUN if [ "$ODM_UID" != 0 ]; then \
        existing_user="$(getent passwd "$ODM_UID" | cut -d: -f1)"; \
        if [ -n "$existing_user" ]; then userdel "$existing_user"; fi; \
        if ! getent group "$ODM_GID" >/dev/null; then groupadd -g "$ODM_GID" developer; fi; \
        useradd -m -d /home/developer -s /bin/bash -u "$ODM_UID" -g "$ODM_GID" developer; \
    else mkdir -p /home/developer; fi && \
    mkdir -p /app /home/developer/.m2 && \
    chown -R "$ODM_UID:$ODM_GID" /app /home/developer

WORKDIR /app
# Explicit Java home keeps the Maven cache consistent for root callers too.
ENV MAVEN_OPTS="-Duser.home=/home/developer"
USER ${ODM_UID}:${ODM_GID}

# Set up display for GUI testing
ENV DISPLAY=:99

# Default command
CMD ["/bin/bash"]
