# ── Stage 1: build the Vite SPA ──────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./

# The lockfile names no platform binary, so one is fetched by hand.
#
# Tailwind v4 pulls lightningcss, whose native binaries ship as optional dependencies — one per
# OS/arch/libc. This lock was generated on Windows and npm recorded only the platform it resolved
# on: it lists eleven optional deps and carries an entry for none of them. Neither `npm ci` nor
# `npm install` will then fetch one on Linux, and the build dies on
# "Cannot find module '../lightningcss.linux-x64-musl.node'".
#
# The version is read from the lightningcss npm actually installed, so the two can never drift:
# bumping Tailwind moves both together. Regenerating the lock inside Linux would fix this image and
# break `npm ci` on the developer's own machine instead — the fix belongs where the platform is
# known, which is here.
RUN npm ci --no-audit --no-fund  && npm install --no-save --no-audit --no-fund       "lightningcss-linux-x64-musl@$(node -p "require('lightningcss/package.json').version")"
COPY . .
RUN npm run build

# ── Stage 2: serve static assets + reverse-proxy /api and /ws to the gateway ──
# The SPA calls relative paths (api.ts forces baseURL=''), so the browser is
# same-origin and nginx forwards API/WS to the gateway over the docker network.
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
