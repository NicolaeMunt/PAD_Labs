FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:22-alpine
WORKDIR /app
COPY --from=build /build/dist ./dist
COPY public ./public
# Terminal mode: <publisherId> <topic> [host] [port]   (inside compose the broker host is "broker")
# Web UI mode:   --ui [brokerHost] [brokerPort] [uiPort]
ENV UI_HOST=0.0.0.0
EXPOSE 3000
ENTRYPOINT ["node", "dist/index.js"]
