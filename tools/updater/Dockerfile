# Signs Glance build artifacts and serves them to tablets on the local network.
#
# The image contains no Android SDK. Google publishes no arm64 Linux build tools, but apksigner is
# a plain Java program, so signing runs natively on both x86_64 and a Raspberry Pi while the parts
# that genuinely need x86_64 (aapt2, zipalign) stay in CI. Keeping Gradle out of this image also
# means the machine holding the signing key never executes third-party build dependencies.
FROM eclipse-temurin:17-jre-noble

ARG BUILD_TOOLS_URL=https://dl.google.com/android/repository/build-tools_r34-linux.zip
ARG BUILD_TOOLS_SHA256=e858c4b60069d0431051b225d384413b1643e1289b00a4825aed347f25bd510f

RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates curl jq unzip python3 \
    && rm -rf /var/lib/apt/lists/*

# Only apksigner.jar is kept; the rest of the archive is x86_64 native tooling this image must not
# depend on. The download is pinned by digest so the signing image is reproducible.
RUN curl -fsSL "$BUILD_TOOLS_URL" -o /tmp/build-tools.zip \
    && echo "$BUILD_TOOLS_SHA256  /tmp/build-tools.zip" | sha256sum -c - \
    && unzip -q -j /tmp/build-tools.zip '*/lib/apksigner.jar' -d /opt/apksigner \
    && rm /tmp/build-tools.zip \
    && java -jar /opt/apksigner/apksigner.jar version

COPY entrypoint.sh /usr/local/bin/glance-updater
RUN chmod +x /usr/local/bin/glance-updater

ENV GLANCE_REPO=""
ENV GLANCE_PUBLIC_URL=""
ENV GLANCE_POLL_SECONDS=300
ENV GLANCE_KEYSTORE=/keys/glance-release.jks
ENV GLANCE_KEY_ALIAS=glance
ENV GLANCE_OUT=/out
ENV GLANCE_PORT=8080
ENV GLANCE_KEEP=5

EXPOSE 8080
VOLUME ["/out"]

ENTRYPOINT ["/usr/local/bin/glance-updater"]
