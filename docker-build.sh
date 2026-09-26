#!/bin/bash
# Simple Docker build script for Open Download Manager
set -e

PROJECT_NAME="open-download-manager"
IMAGE_NAME="${ODM_IMAGE_NAME:-odm-dev}"

# Root callers (act job containers) must not clobber the developer's image
# with a USER 0 variant: build under a separate tag. worktree_write_guard
# below repairs the file ownership side effect.
if [ "$(id -u)" = 0 ] && [ -z "${ODM_IMAGE_NAME:-}" ]; then
    IMAGE_NAME="odm-dev-uid0"
fi

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

log() {
    echo -e "${GREEN}[ODM]${NC} $1"
}

# The image uses the caller's UID/GID, so ordinary private cache permissions work.
prepare_m2() {
    mkdir -p "$HOME/.m2"
}

# X11 authentication forwarder. Wayland/Xwayland sessions gate the display
# behind an Xauthority token (e.g. /run/user/*/.mutter-Xwaylandauth.*). Docker
# containers must receive that token or the X server rejects the connection
# with "Authorization required, but no authorization protocol specified".
# The token is copied to a stable host path before each container launch.
xauth_args() {
    if [ -z "$DISPLAY" ] || [ -z "$XAUTHORITY" ] || [ ! -f "$XAUTHORITY" ]; then
        return
    fi
    local host_auth="$HOME/.odm-xauthority"
    if ! cp "$XAUTHORITY" "$host_auth" 2>/dev/null; then
        return
    fi
    echo " -e XAUTHORITY=/tmp/odm-xauthority -v $host_auth:/tmp/odm-xauthority:rw"
}

# Containers spawned by a root caller run as root (ODM_UID=0 image) and would
# otherwise leave root-owned files in the bind-mounted worktree. When the
# caller is root, prepend an EXIT trap that hands anything root-owned back to
# the worktree owner (visible inside the container as the owner of /app).
worktree_write_guard() {
    if [ "$(id -u)" = 0 ]; then
        printf '%s' "trap 'chown -R --from=0:0 \$(stat -c %u:%g /app) /app 2>/dev/null || true' EXIT; "
    fi
}

# Run application
run() {
    prepare_m2
    local xa="$(xauth_args)"
    log "Running application with GUI..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$(pwd)/docker-data:/app/data" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e DISPLAY=$DISPLAY \
        $xa \
        -v /tmp/.X11-unix:/tmp/.X11-unix:rw \
        --ipc=host \
        $IMAGE_NAME \
        bash -c "$(worktree_write_guard)"'mvn -q -pl odm-gtk4 -am package -DskipTests=true && mvn -q -pl odm-gtk4 dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt && java -Djava.util.logging.level=FINE -Djava.util.logging.ConsoleHandler.level=FINE -cp "odm-gtk4/target/classes:core/target/classes:$(cat /tmp/cp.txt)" org.odm.gtk4.OdmApplication'
}

# Run application in debug mode
debug() {
    prepare_m2
    local xa="$(xauth_args)"
    log "Running application in debug mode (port 5005) with GUI..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e DISPLAY=$DISPLAY \
        $xa \
        -v /tmp/.X11-unix:/tmp/.X11-unix:rw \
        -p 5005:5005 \
        --ipc=host \
        $IMAGE_NAME \
        bash -c "$(worktree_write_guard)"'mvn -q -pl odm-gtk4 -am package -DskipTests=true && mvn -q -pl odm-gtk4 dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt && java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=0.0.0.0:5005 -Djava.util.logging.level=FINE -Djava.util.logging.ConsoleHandler.level=FINE -cp "odm-gtk4/target/classes:core/target/classes:$(cat /tmp/cp.txt)" org.odm.gtk4.OdmApplication'
}

# Build the Docker image. CI workflows export ODM_CACHE_FROM/ODM_CACHE_TO
# (e.g. type=gha) to persist the layer cache across ephemeral runners; the
# image is still loaded into the local daemon for the run/test/package steps.
# Locally (no cache env) this is a plain `docker build` as before.
# The gha cache backend needs the real GitHub cache service. Local act runs
# emulate it with an artifact server that buildx cannot reach (dial tcp
# connection refused on import and export), and transient cache outages must
# not fail the whole job when the image itself builds, so skip unavailable
# caches up front and retry without the cache when a cached build fails.
build() {
    local args=(--build-arg "ODM_UID=$(id -u)" --build-arg "ODM_GID=$(id -g)" -t "$IMAGE_NAME" .)
    if [[ -z "${ODM_CACHE_FROM:-}${ODM_CACHE_TO:-}" ]]; then
        log "Building Docker image..."
        docker build "${args[@]}"
        return
    fi
    if [[ "${ODM_CACHE_FROM:-}${ODM_CACHE_TO:-}" == *"type=gha"* ]]; then
        if [[ "${ACT:-}" == "true" ]]; then
            log "GHA layer cache requested but running under act; building without layer cache..."
            docker build "${args[@]}"
            return
        fi
        if [[ -z "${ACTIONS_CACHE_URL:-}" ]]; then
            log "GHA layer cache requested but ACTIONS_CACHE_URL is unset; building without layer cache..."
            docker build "${args[@]}"
            return
        fi
    fi
    local cache_args=()
    [[ -n "${ODM_CACHE_FROM:-}" ]] && cache_args+=(--cache-from "$ODM_CACHE_FROM")
    [[ -n "${ODM_CACHE_TO:-}" ]] && cache_args+=(--cache-to "$ODM_CACHE_TO")
    log "Building Docker image (external cache)..."
    if docker buildx build --load "${cache_args[@]}" "${args[@]}"; then
        return
    fi
    log "External cache build failed; retrying without layer cache..."
    docker build "${args[@]}"
}

# Start development container
dev() {
    prepare_m2
    local xa="$(xauth_args)"
    log "Starting development container..."
    docker run --init -it --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e DISPLAY=$DISPLAY \
        $xa \
        -v /tmp/.X11-unix:/tmp/.X11-unix:rw \
        --name odm-dev \
        $IMAGE_NAME
}

# Run the default suite without integration/E2E or performance tests
test() {
    prepare_m2
    log "Running tests..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e PROXYCHAINS_AVAILABLE=true \
        -e ENABLE_NETWORK_TESTS=true \
        $IMAGE_NAME \
        bash -c "$(worktree_write_guard)Xvfb :99 -screen 0 1024x768x24 -ac +extension GLX +render -noreset > /dev/null 2>&1 & sleep 2 && mvn test"
}

# Include integration/E2E (-Pintegration clears the filename excludes).
# Performance benchmarks remain excluded unless -Pperf is selected.
test_integration() {
    prepare_m2
    log "Running integration tests..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e PROXYCHAINS_AVAILABLE=true \
        -e ENABLE_NETWORK_TESTS=true \
        $IMAGE_NAME \
        bash -c "$(worktree_write_guard)Xvfb :99 -screen 0 1024x768x24 -ac +extension GLX +render -noreset > /dev/null 2>&1 & sleep 2 && mvn test -Pintegration"
}

# Run only performance benchmarks, without coverage instrumentation.
test_perf() {
    prepare_m2
    log "Running performance benchmarks..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        $IMAGE_NAME \
        bash -c "$(worktree_write_guard)Xvfb :99 -screen 0 1024x768x24 -ac +extension GLX +render -noreset > /dev/null 2>&1 & sleep 2 && mvn test -Pperf"
}

# Build application
compile() {
    prepare_m2
    log "Building application..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        $IMAGE_NAME \
        bash -c "$(worktree_write_guard)mvn clean compile package -DskipTests=true"
}



# Create packages
package() {
    prepare_m2
    local version="${1:-0.3.0}"
    if [[ ! "$version" =~ ^[0-9]+([.][0-9]+){1,3}$ ]]; then
        echo "Invalid package version: expected numeric dotted version" >&2
        return 2
    fi
    log "Creating packages (version ${version})..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        $IMAGE_NAME \
        bash -c "$(worktree_write_guard)/app/packaging/build-packages.sh $version"
}

# Verify the built packages (same container requirements as package).
verify() {
    local version="${1:-0.3.0}"
    if [[ ! "$version" =~ ^[0-9]+([.][0-9]+){1,3}$ ]]; then
        echo "Invalid package version: expected numeric dotted version" >&2
        return 2
    fi
    log "Verifying packages (version ${version})..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        $IMAGE_NAME \
        bash -c "$(worktree_write_guard)/app/packaging/verify-packages.sh $version"
}

# Clean up. Only touches ODM resources: the dev container (if left over)
# and the dev image. Never a global prune, which would delete unrelated
# containers, networks and dangling images from other projects.
clean() {
    log "Cleaning up..."
    docker rm -f odm-dev 2>/dev/null || true
    docker rmi $IMAGE_NAME 2>/dev/null || true
}

# Show help
help() {
    echo "Usage: $0 [COMMAND]"
    echo ""
    echo "Commands:"
    echo "  build     Build Docker image"
    echo "  dev       Start development container"
    echo "  test      Run tests excluding integration/E2E and performance suites"
    echo "  test-integration  Run tests including integration/E2E suites (-Pintegration)"
    echo "  test-perf Run performance benchmarks only (-Pperf)"
    echo "  compile   Build application"
    echo "  run       Run application with GUI support"
    echo "  debug     Run application in debug mode (port 5005)"
    echo "  package   Create distribution packages (.deb/.rpm/.pkg.tar.zst/.AppImage)"
    echo "  verify    Verify the built distribution packages"
    echo "  clean     Clean up Docker resources"
    echo "  help      Show this help"
}

# Main
case "${1:-help}" in
    build)   build ;;
    dev)     build && dev ;;
    test)    build && test ;;
    test-integration) build && test_integration ;;
    test-perf) build && test_perf ;;
    compile) build && compile ;;
    run)     build && run ;;
    debug)   build && debug ;;
    package) shift; build && package "$@" ;;
    verify)  shift; build && verify "$@" ;;
    clean)   clean ;;
    help)    help ;;
    *)       help ;;
esac
