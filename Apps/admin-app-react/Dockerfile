# ── Stage 1: build the Vite SPA ──────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ── Stage 2: serve static assets + reverse-proxy /api and /ws to the gateway ──
# The SPA calls relative paths (api.ts forces baseURL=''), so the browser is
# same-origin and nginx forwards API/WS to the gateway over the docker network.
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
