# syntax=docker/dockerfile:1.7

FROM node:20-bookworm-slim AS build

ARG PNPM_VERSION=10.34.5
ARG VITE_BASE_URL=
ENV NODE_ENV=production
ENV VITE_BASE_URL=${VITE_BASE_URL}

RUN npm install --global pnpm@${PNPM_VERSION}

WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

COPY . .
RUN pnpm build:docker

FROM nginx:alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
