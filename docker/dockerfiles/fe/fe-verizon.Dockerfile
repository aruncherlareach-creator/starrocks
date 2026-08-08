# Multi-stage Dockerfile for the Verizon StarRocks FE build.
# Stage 1 compiles the FE inside the dev-env image so no pre-built
# artifacts need to be passed in from CI.
#
# Build (from repo root):
#   docker build -f docker/dockerfiles/fe/fe-verizon.Dockerfile -t starrocks-fe:local .

# ---------------------------------------------------------------------------
# Stage 1 — compile
# ---------------------------------------------------------------------------
FROM starrocks/dev-env-ubuntu:4.1.4 AS builder
WORKDIR /starrocks
COPY . .
RUN ./build.sh --fe -j "$(nproc)"

# ---------------------------------------------------------------------------
# Stage 2 — runtime (mirrors fe-ubuntu.Dockerfile base_image)
# ---------------------------------------------------------------------------
FROM ubuntu:24.04

ARG STARROCKS_ROOT=/opt/starrocks
ARG USER=starrocks
ARG GROUP=starrocks
ARG RUN_AS_USER=root

RUN apt-get update -y && apt-get install -y --no-install-recommends \
        openjdk-17-jdk mysql-client tzdata locales tini libssl-dev netcat-traditional \
        curl vim tree net-tools less pigz rclone && \
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

COPY --from=builder --chown=$USER:$GROUP /starrocks/output/fe $STARROCKS_ROOT/fe/
COPY --chown=$USER:$GROUP docker/dockerfiles/fe/*.sh $STARROCKS_ROOT/

RUN mkdir -p $STARROCKS_ROOT/fe/meta

ENTRYPOINT ["/usr/bin/tini-static", "--"]
