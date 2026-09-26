FROM eclipse-temurin:25-jdk

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
    # GTK libraries for java-gi (GTK4)
    libgtk-4-dev \
    libglib2.0-dev \
    pkg-config \
    # External dependencies (matching debian/control versions)
    aria2 \
    httrack \
    proxychains4 \
    tor \
    yt-dlp \
    subliminal \
    # X11 for GUI testing
    xvfb \
    x11-utils \
    dbus-x11 \
    # Package building tools
    dpkg-dev \
    fakeroot \
    rpm \
    file \
    zstd \
    libarchive-tools \
    # Utilities
    vim \
    tree \
    && rm -rf /var/lib/apt/lists/*

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
