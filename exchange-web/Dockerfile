# Use Debian/glibc for the build, avoiding the Alpine/musl Rollup native parser path on ARM64.
FROM node:22-bookworm-slim AS build
WORKDIR /web
COPY exchange-web/package*.json ./
RUN npm ci --no-audit --no-fund
COPY exchange-web/index.html exchange-web/vite.config.js ./
COPY exchange-web/src ./src
RUN npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine
COPY exchange-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
