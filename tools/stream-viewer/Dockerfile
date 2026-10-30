# The stream viewer (scripts/dev.sh viewer up): a headless Chromium that watches streams through WebTS, for testing
# PocketTS's screen and camera sharing without a second device. Only for testing.
FROM node:22-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends chromium fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /viewer
COPY viewer.mjs page.js ./
USER node
CMD ["node", "viewer.mjs"]
