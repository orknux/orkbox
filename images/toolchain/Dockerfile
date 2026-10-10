# orknux/codebox-toolchain: the default image for docker sessions.
#
# Builds Maven, Gradle, Node.js, C and C++ projects out of the box. Commands
# run as root, so agents can apt-get install anything else they need.
#
#   docker build -t orknux/codebox-toolchain:latest images/toolchain
#
# Any image can be used instead, as long as it has: sh, setsid (util-linux),
# GNU coreutils, findutils and tar. ripgrep and git are used when present.

ARG JDK_IMAGE=eclipse-temurin:25-jdk-noble
ARG NODE_IMAGE=node:24-bookworm-slim

FROM ${JDK_IMAGE} AS jdk
FROM ${NODE_IMAGE} AS node

FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl wget gnupg git git-lfs openssh-client \
        unzip zip xz-utils bzip2 zstd file jq ripgrep fd-find less tree procps psmisc nano vim-tiny \
        build-essential gcc g++ gdb clang clang-format clang-tidy lld lldb \
        make cmake ninja-build meson pkg-config autoconf automake libtool ccache \
        libssl-dev zlib1g-dev \
        python3 python3-pip python3-venv python-is-python3 \
    && ln -s /usr/bin/fdfind /usr/local/bin/fd \
    && rm -rf /var/lib/apt/lists/*

# Java
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk

# Maven
ARG MAVEN_VERSION=3.9.16
RUN curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        | tar -xz -C /opt \
    && ln -s "/opt/apache-maven-${MAVEN_VERSION}" /opt/maven

# Gradle
ARG GRADLE_VERSION=9.8.1
RUN curl -fsSLo /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && unzip -q /tmp/gradle.zip -d /opt \
    && ln -s "/opt/gradle-${GRADLE_VERSION}" /opt/gradle \
    && rm /tmp/gradle.zip

# Node.js with npm, plus pnpm and yarn through corepack
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && npm install -g corepack \
    && corepack enable \
    && COREPACK_ENABLE_DOWNLOAD_PROMPT=0 corepack prepare pnpm@latest --activate \
    && COREPACK_ENABLE_DOWNLOAD_PROMPT=0 corepack prepare yarn@stable --activate \
    && pnpm --version && yarn --version

ENV PATH=/opt/java/openjdk/bin:/opt/maven/bin:/opt/gradle/bin:$PATH \
    COREPACK_ENABLE_DOWNLOAD_PROMPT=0 \
    LANG=C.UTF-8 \
    CI=true

WORKDIR /workspace
CMD ["sleep", "infinity"]
