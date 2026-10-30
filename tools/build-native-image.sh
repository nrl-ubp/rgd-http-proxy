#!/usr/bin/env bash
#
# Builds the container image of the native executables of the proxy (rgd-proxy-hub), see the README,
# "Native build and container image".
#
# Build the executables first, each on a host of its architecture:
#   mvn package -Dnative -DskipTests   -> rgd-proxy-hub/target/rgd-proxy-hub-<version>-runner-linux-<arch>
# (or, on a Linux host with GraalVM, with -Dnative.graalvm-home=<GraalVM home>), then gather them in
# rgd-proxy-hub/target and run this script. Only the linux executables are packaged: a macos or windows
# one, built by a local GraalVM on such a host, runs there only. With one executable it builds a
# single-architecture image; with both amd64 and arm64 it builds a multi-architecture image (manifest
# list). The Dockerfile has no RUN instruction, so no emulation is needed for the other architecture.
#
# Usage: tools/build-native-image.sh [-t <image>[:<tag>]] [--push] [--runtime podman|docker]
#   -t, --tag      image name (default rgd-proxy-hub:native)
#   --push         push the image (or the multi-arch manifest list) to its registry
#   --runtime      podman or docker (default: podman when installed, docker otherwise)
set -euo pipefail

IMAGE="rgd-proxy-hub:native"
PUSH=false
RUNTIME=""

usage() {
    # the comment header of this script, without its '#'
    awk 'NR > 2 && /^#/ { sub(/^# ?/, ""); print; next } NR > 2 { exit }' "$0"
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        -t|--tag) IMAGE="$2"; shift 2 ;;
        --push) PUSH=true; shift ;;
        --runtime) RUNTIME="$2"; shift 2 ;;
        -h|--help) usage 0 ;;
        *) echo "Unknown option: $1" >&2; usage 2 ;;
    esac
done

if [ -z "$RUNTIME" ]; then
    if command -v podman >/dev/null 2>&1; then RUNTIME=podman
    elif command -v docker >/dev/null 2>&1; then RUNTIME=docker
    else echo "Neither podman nor docker is installed." >&2; exit 1
    fi
fi

HUB="$(cd "$(dirname "$0")/.." && pwd)/rgd-proxy-hub"
DOCKERFILE=src/main/docker/Dockerfile.native-micro
cd "$HUB"

ARCHS=()
for arch in amd64 arm64; do
    count=$(find target -maxdepth 1 -name "*-runner-linux-$arch" -type f 2>/dev/null | wc -l | tr -d ' ')
    if [ "$count" -gt 1 ]; then
        echo "Several $arch executables in $HUB/target, keep only one:" >&2
        find target -maxdepth 1 -name "*-runner-linux-$arch" -type f >&2
        exit 1
    fi
    [ "$count" -eq 1 ] && ARCHS+=("$arch")
done
if [ ${#ARCHS[@]} -eq 0 ]; then
    echo "No Linux native executable in $HUB/target: build it with 'mvn package -Dnative -DskipTests'." >&2
    others=$(find target -maxdepth 1 -name "*-runner-*-*" -type f ! -name "*-runner-linux-*" 2>/dev/null)
    if [ -n "$others" ]; then
        echo "Found only executables for another OS, which cannot go in a Linux image:" >&2
        echo "$others" >&2
    fi
    exit 1
fi
PLATFORMS=$(printf 'linux/%s,' "${ARCHS[@]}")
PLATFORMS=${PLATFORMS%,}
echo "Building $IMAGE for $PLATFORMS with $RUNTIME"

if [ "$RUNTIME" = podman ]; then
    if [ ${#ARCHS[@]} -eq 1 ]; then
        podman build --platform "$PLATFORMS" -f "$DOCKERFILE" -t "$IMAGE" .
        if $PUSH; then podman push "$IMAGE"; fi
    else
        # a manifest list is rebuilt from scratch: drop the previous one, or the image of that name
        if podman manifest exists "$IMAGE" 2>/dev/null; then
            podman manifest rm "$IMAGE" >/dev/null
        elif podman image exists "$IMAGE" 2>/dev/null; then
            podman untag "$IMAGE" "$IMAGE"
        fi
        podman build --platform "$PLATFORMS" -f "$DOCKERFILE" --manifest "$IMAGE" .
        if $PUSH; then podman manifest push --all "$IMAGE" "docker://$IMAGE"; fi
    fi
else
    if [ ${#ARCHS[@]} -eq 1 ]; then
        if $PUSH; then OUTPUT=--push; else OUTPUT=--load; fi
        docker buildx build --platform "$PLATFORMS" -f "$DOCKERFILE" -t "$IMAGE" "$OUTPUT" .
    else
        if ! $PUSH; then
            echo "docker buildx cannot load a multi-arch image locally: add --push (or use podman)." >&2
            exit 1
        fi
        docker buildx build --platform "$PLATFORMS" -f "$DOCKERFILE" -t "$IMAGE" --push .
    fi
fi
echo "Built $IMAGE ($PLATFORMS)"
