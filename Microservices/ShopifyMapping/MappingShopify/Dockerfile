# ──────────────────────────────────────────────────────────────
# MappingShopify — Dockerfile
# Build context: ShopifyMapping/MappingShopify/
# ──────────────────────────────────────────────────────────────

FROM node:20 AS builder

WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build
RUN npm prune --production

FROM node:20-slim
WORKDIR /app
COPY --from=builder /app/dist         ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json  ./

ENV NODE_ENV=production
ENV PORT=3400

EXPOSE 3400
CMD ["node", "dist/main"]
