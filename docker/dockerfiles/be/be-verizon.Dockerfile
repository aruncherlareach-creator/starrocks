# Multi-stage Dockerfile for the Verizon StarRocks BE build.
# Stage 1 compiles the BE inside the dev-env image so no pre-built
# artifacts need to be passed in from CI.
#
# Build (from repo root):
#   docker build -f docker/dockerfiles/be/be-verizon.Dockerfile -t starrocks-be:local .

# ---------------------------------------------------------------------------
# Stage 1 — compile
# ---------------------------------------------------------------------------
FROM starrocks/dev-env-ubuntu:4.1.4 AS builder
WORKDIR /starrocks
COPY . .
# H3 was added after dev-env:4.1.4 was cut — build it from the tarball
# pre-downloaded by CI into docker/artifacts/ (avoids GitHub network access
# from inside the Kaniko build pod on the Verizon network).
RUN TP_INSTALL=/var/local/thirdparty/installed && \
    TP_SRC=/var/local/thirdparty/src && \
    mkdir -p "$TP_SRC" && \
    cp /starrocks/docker/artifacts/h3-4.1.0.tar.gz "$TP_SRC/h3-4.1.0.tar.gz" && \
    cd "$TP_SRC" && \
    tar xzf h3-4.1.0.tar.gz && \
    mkdir -p h3-4.1.0/build && \
    cd h3-4.1.0/build && \
    cmake -DCMAKE_INSTALL_PREFIX="$TP_INSTALL" \
          -DCMAKE_INSTALL_LIBDIR=lib \
          -DBUILD_SHARED_LIBS=OFF \
          -DENABLE_TESTING=OFF \
          -DENABLE_COVERAGE=OFF \
          -DENABLE_BENCHMARKS=OFF \
          -DENABLE_EXAMPLES=OFF \
          -DENABLE_DOCS=OFF \
          .. && \
    make -j"$(nproc)" && \
    make install
RUN git config --global --add safe.directory /starrocks && \
    ./build.sh --be -j "$(nproc)" && \
    rm -f output/be/lib/starrocks_be.debuginfo

# ---------------------------------------------------------------------------
# Stage 2 — runtime (mirrors be-ubuntu.Dockerfile base_image)
# ---------------------------------------------------------------------------
FROM ubuntu:24.04

ARG STARROCKS_ROOT=/opt/starrocks
ARG USER=starrocks
ARG GROUP=starrocks
ARG RUN_AS_USER=root

RUN apt-get update -y && apt-get install -y --no-install-recommends \
        openjdk-17-jdk mysql-client tzdata locales tini libssl-dev \
        curl vim tree net-tools less pigz inotify-tools rclone gdb && \
    ln -fs /usr/share/zoneinfo/UTC /etc/localtime && \
    dpkg-reconfigure -f noninteractive tzdata && \
    locale-gen en_US.UTF-8 && \
    rm -rf /var/lib/apt/lists/*

RUN touch /.dockerenv && ARCH="$(uname -m)" && cd /lib/jvm && \
    if [ "$ARCH" = "aarch64" ]; then \
        ln -s java-17-openjdk-arm64 java-17-openjdk; \
    else \
        ln -s java-17-openjdk-amd64 java-17-openjdk; \
    fi
ENV JAVA_HOME=/lib/jvm/java-17-openjdk

WORKDIR $STARROCKS_ROOT

RUN if getent group 1000 >/dev/null 2>&1; then \
        gname=$(getent group 1000 | cut -d: -f1) && \
        if [ "$gname" != "$GROUP" ]; then groupmod -n "$GROUP" "$gname"; fi; \
    else \
        groupadd --gid 1000 "$GROUP"; \
    fi && \
    if [ "$USER" != "root" ]; then \
        if id 1000 >/dev/null 2>&1; then \
            username=$(id -un 1000) && \
            if [ "$username" != "$USER" ]; then usermod -l "$USER" "$username"; fi && \
            usermod -g "$GROUP" "$USER"; \
        else \
            useradd --no-create-home --uid 1000 --gid "$GROUP" --shell /usr/sbin/nologin "$USER"; \
        fi; \
    fi && \
    chown -R "$USER":"$GROUP" "$STARROCKS_ROOT"

USER $USER

COPY --from=builder --chown=$USER:$GROUP /starrocks/output/be $STARROCKS_ROOT/be/
COPY --chown=$USER:$GROUP docker/dockerfiles/be/*.sh $STARROCKS_ROOT/

RUN mkdir -p $STARROCKS_ROOT/be/storage && ln -sfT be $STARROCKS_ROOT/cn

ENTRYPOINT ["/usr/bin/tini-static", "--"]
