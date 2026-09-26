# A desktop baseline with no Java, GTK4 or download tools. Keep the graphics,
# audio and font libraries that AppImage's excludelist reserves for the host.
ARG DISTRO=22.04
FROM ubuntu:${DISTRO}
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
    xvfb xauth xdotool dbus-x11 fontconfig fonts-dejavu-core \
    libharfbuzz0b libfribidi0 libwayland-client0 libjack0 libusb-1.0-0 \
    libasound2-dev libgbm1 libgl1 libegl1 libgles2 file python3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --shell /bin/bash odm-smoke
USER odm-smoke
