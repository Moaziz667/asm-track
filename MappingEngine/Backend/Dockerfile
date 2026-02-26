# ──────────────────────────────────────────────────────────────
# MappingEngine — Dockerfile  (build context: MappingEngine/)
# Stages: vue-builder → api-builder → runtime
# ──────────────────────────────────────────────────────────────

# ── Stage 0: Build Vue 3 frontend ─────────────────────────────
FROM node:20-slim AS vue-builder
WORKDIR /ui
COPY Frontend/package*.json ./
RUN npm install
COPY Frontend/ ./
RUN npm run build
# dist/ is now at /ui/dist

# ── Stage 1: Build NestJS API ──────────────────────────────────
# node:20 (Debian Bookworm) already ships python3, make, g++ — needed by better-sqlite3
FROM node:20 AS builder

WORKDIR /app

# Install dependencies first (layer caching)
COPY Backend/package*.json ./
RUN npm install

# Copy source and compile TypeScript
COPY Backend/ ./
RUN npm run build

# Prune dev dependencies
RUN npm prune --production

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM node:20-slim

WORKDIR /app

# Copy compiled output + production node_modules
COPY --from=builder /app/dist       ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json  ./

# Copy built Vue frontend (dist/) — served by NestJS ServeStatic
COPY --from=vue-builder /ui/dist ./frontend
# Keep original logo accessible at /avatar.png
COPY Frontend/avatar.png ./frontend/avatar.png

# Runtime environment
ENV NODE_ENV=production
ENV PORT=3200
ENV STATIC_ROOT=/app/frontend
ENV DATA_DIR=/app/data

EXPOSE 3200

# Data directory will be a Docker volume for SQLite persistence
VOLUME ["/app/data"]

CMD ["node", "dist/main"]
