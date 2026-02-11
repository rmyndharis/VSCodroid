# Force x86_64: the NDK linux download only ships x86_64 host toolchains.
# On Apple Silicon, Docker Desktop runs this via Rosetta 2.
FROM --platform=linux/amd64 ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# Install build dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    curl \
    git \
    libssl-dev \
    ninja-build \
    pkg-config \
    python3 \
    python3-pip \
    unzip \
    wget \
    xz-utils \
    && rm -rf /var/lib/apt/lists/*

# Install Android NDK r27
ENV ANDROID_NDK_VERSION=r27c
ENV ANDROID_NDK_HOME=/opt/android-ndk
RUN mkdir -p /opt && \
    cd /tmp && \
    wget -q "https://dl.google.com/android/repository/android-ndk-${ANDROID_NDK_VERSION}-linux.zip" && \
    unzip -q "android-ndk-${ANDROID_NDK_VERSION}-linux.zip" -d /opt && \
    mv "/opt/android-ndk-${ANDROID_NDK_VERSION}" "$ANDROID_NDK_HOME" && \
    rm "android-ndk-${ANDROID_NDK_VERSION}-linux.zip"

# Set NDK toolchain variables
ENV NDK_TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
ENV CC=$NDK_TOOLCHAIN/bin/aarch64-linux-android28-clang
ENV CXX=$NDK_TOOLCHAIN/bin/aarch64-linux-android28-clang++
ENV AR=$NDK_TOOLCHAIN/bin/llvm-ar
ENV RANLIB=$NDK_TOOLCHAIN/bin/llvm-ranlib
ENV STRIP=$NDK_TOOLCHAIN/bin/llvm-strip
ENV NM=$NDK_TOOLCHAIN/bin/llvm-nm
ENV AS=$NDK_TOOLCHAIN/bin/llvm-as
ENV LD=$NDK_TOOLCHAIN/bin/ld.lld

ENV PATH=$NDK_TOOLCHAIN/bin:$PATH

WORKDIR /build

CMD ["/bin/bash"]
