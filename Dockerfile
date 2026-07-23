# ---- Build stage ----

FROM node:22-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY index.html ./
COPY tsconfig.json vite.config.ts ./
COPY metadata.json ./
COPY server.ts db.ts orderPrompt.ts orderTools.ts ./
COPY src ./src
# COPY public ./public
# COPY .env ./
# COPY assets ./assets

RUN npm run build

# ---- Runtime stage ----
FROM node:22-alpine AS runtime

WORKDIR /app

ENV NODE_ENV=production
ENV PORT=3000

COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY --from=build /app/dist ./dist

EXPOSE 3000

# Pass secrets at runtime (do not bake keys into the image):
#   -e GEMINI_API_KEY=... -e DATABASE_URL=...
CMD ["node", "dist/server.cjs"]
