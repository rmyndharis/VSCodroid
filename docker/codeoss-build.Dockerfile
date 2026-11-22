# Build host for compiling Code - OSS into a vscode-reh-web server tree.
#
# Linux, not macOS: @vscode/ripgrep's postinstall branches on os.platform() and
# node-gyp picks its flavour from the host, so a macOS shell produces a tree that
# cannot run on the device.
#
# Left at the host's own architecture on purpose. Our target is
# vscode-reh-web-linux-arm64, so on an arm64 machine this builds natively and the
# remote/ native modules (which remote/.npmrc forces to build_from_source) need
# no qemu at all. Microsoft's own pipeline reaches for multiarch/qemu-user-static
# precisely because it builds arm64 on x64; we can skip that whole layer.
#
# The base image is pinned by digest, for the reason the Node tarball below is:
# a tag is whatever the registry serves that day, and this image compiles every
# server tree an APK ships. To move it, read the index digest the tag names now
# (`docker buildx imagetools inspect ubuntu:24.04`) and replace the one here.
FROM ubuntu:24.04@sha256:9c7f200c07e3d4a03e7249d2c16bf66a51120fb876aa2242cf31451412543289

ENV DEBIAN_FRONTEND=noninteractive

# Matches .nvmrc at the VS Code tag being built: the Node this build host runs.
# The Node that ends up on the device is Termux's nodejs-lts (download-node.sh),
# which has to match the tag's remote/.npmrc `target` in the major. At 1.133.0 all
# three were 24.18.0; at 1.139.1 .nvmrc is still 24.18.0 while `target` is
# 24.20.0. Read both at the tag rather than assuming.
ARG NODE_VERSION=24.18.0

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        curl \
        git \
        jq \
        libkrb5-dev \
        libsecret-1-dev \
        libx11-dev \
        libxkbfile-dev \
        pkg-config \
        python3 \
        python3-setuptools \
        xz-utils \
    && rm -rf /var/lib/apt/lists/*

# What node-v$NODE_VERSION-linux-<arch>.tar.xz must hash to, one per build
# architecture this file accepts. Pinned here rather than read from the
# SHASUMS256.txt beside the tarball, because a file fetched from the same host
# as the tarball attests nothing the host could not also change. Moving
# NODE_VERSION means replacing both, from the release's SHASUMS256.txt; the
# arm64 one is the digest download-npm.sh pins while the two versions agree.
ARG NODE_SHA256_ARM64=58c9520501f6ae2b52d5b210444e24b9d0c029a58c5011b797bc1fe7105886f6
ARG NODE_SHA256_X64=55aa7153f9d88f28d765fcdad5ae6945b5c0f98a36881703817e4c450fa76742

# Checked against the digest pinned above rather than piped straight into tar.
# download-npm.sh and build-native-addons.sh hold their nodejs.org artifacts to
# the same bar, and this one has further reach than either: it is the Node that
# compiles the server tree every APK ships, so a tampered host runtime is baked
# into the output rather than confined to a build step.
#
# An empty digest is refused before sha256sum sees it, so a build argument
# cleared by mistake fails closed rather than comparing against nothing.
RUN set -eux; \
    case "$(dpkg --print-architecture)" in \
        arm64) node_arch=arm64; node_sha256="$NODE_SHA256_ARM64" ;; \
        amd64) node_arch=x64; node_sha256="$NODE_SHA256_X64" ;; \
        *) echo "unsupported build arch: $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac; \
    test -n "$node_sha256"; \
    tarball="node-v${NODE_VERSION}-linux-${node_arch}.tar.xz"; \
    cd /tmp; \
    curl -fsSL -o "$tarball" "https://nodejs.org/dist/v${NODE_VERSION}/$tarball"; \
    echo "$node_sha256  $tarball" | sha256sum -c -; \
    tar -xJf "$tarball" -C /usr/local --strip-components=1; \
    rm -f "$tarball"; \
    node --version; \
    npm --version

# gulp is invoked with --max-old-space-size=8192 by VS Code's own npm script, so
# the container needs headroom well above the default.
ENV NODE_OPTIONS=--max-old-space-size=8192

WORKDIR /work
CMD ["/bin/bash"]
