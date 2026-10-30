# The build environment: Java and the Android SDK, so nothing has to be installed on the developer's own machine.
# Used by scripts/dev.sh (and by CI). Gradle itself comes through the wrapper (./gradlew) into a cache volume.
FROM eclipse-temurin:25-jdk-noble

ARG CMDLINE_TOOLS=15859902
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG PLATFORM=android-37.0
ARG BUILD_TOOLS=37.0.0

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip git curl ca-certificates python3 \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSLo /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
 && echo "${CMDLINE_TOOLS_SHA256}  /tmp/tools.zip" | sha256sum -c - \
 && unzip -q /tmp/tools.zip -d /tmp/tools \
 && mv /tmp/tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm -rf /tmp/tools /tmp/tools.zip

# Building needs the Android SDK license accepted (build arg, so it's a deliberate choice).
ARG ACCEPT_ANDROID_SDK_LICENSE=no
RUN [ "$ACCEPT_ANDROID_SDK_LICENSE" = "yes" ] || { echo "Build with --build-arg ACCEPT_ANDROID_SDK_LICENSE=yes"; exit 1; } \
 && yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install "platforms;${PLATFORM}" "build-tools;${BUILD_TOOLS}" "platform-tools" > /dev/null

ENV GRADLE_USER_HOME=/gradle
WORKDIR /work
