# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM node:20 AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build
RUN npm prune --production

# ── Stage 2: runtime ────────────────────────────────────────────────────────
FROM node:20-slim
WORKDIR /app
COPY --from=builder /app/dist        ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json  ./
EXPOSE 3300
CMD ["node", "dist/main"]
