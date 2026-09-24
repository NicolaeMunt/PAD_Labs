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
# Arguments: <publisherId> <topic> [host] [port]  (inside compose the broker host is "broker")
ENTRYPOINT ["node", "dist/index.js"]
