# ──────────────────────────────────────────────────────────────
# MappingShopify — Dockerfile
# Build context: ShopifyMapping/MappingShopify/
# ──────────────────────────────────────────────────────────────

FROM node:20 AS builder

WORKDIR /app
ARG GITLAB_TOKEN
COPY package*.json ./
RUN echo "@asm:registry=https://gitlab.com/api/v4/projects/79824197/packages/npm/" > .npmrc \
    && echo "//gitlab.com/api/v4/projects/79824197/packages/npm/:_authToken=${GITLAB_TOKEN}" >> .npmrc
RUN npm install
RUN rm -f .npmrc
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
