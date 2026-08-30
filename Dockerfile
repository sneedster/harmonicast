# ---- Build stage ----
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- Server stage ----
FROM node:20-alpine
WORKDIR /app

ARG VCS_REF=unknown
LABEL org.opencontainers.image.source="https://github.com/sneedster/harmonicast" \
      org.opencontainers.image.revision="$VCS_REF"

# Install server dependencies
COPY server/package.json server/package-lock.json ./server/
RUN cd server && npm ci --omit=dev

# Copy built frontend
COPY --from=build /app/dist ./dist

# Copy server source
COPY server/ ./server/
COPY --chmod=755 harmonicast-entrypoint.sh /usr/local/bin/harmonicast-entrypoint

RUN apk add --no-cache su-exec && mkdir -p /app/data && chown -R node:node /app

ENV NODE_ENV=production
ENV PORT=3001
ENV DATA_DIR=/app/data

EXPOSE 3001

WORKDIR /app/server
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:3001/api/auth/config').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"
ENTRYPOINT ["/usr/local/bin/harmonicast-entrypoint"]
CMD ["node", "--import", "tsx", "index.ts"]
