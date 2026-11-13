FROM node:26-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-26 AS backend
WORKDIR /build
COPY pom.xml ./
COPY collector-contracts/ ./collector-contracts/
COPY github-collector/ ./github-collector/
COPY backend/ ./backend/
COPY --from=frontend /frontend/dist ./backend/src/main/resources/static
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app \
  && mkdir -p /var/log/app \
  && chown -R app:app /var/log/app
COPY --from=backend /build/backend/target/repo-growth-monitor-*.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh && chown app:app /app/docker-entrypoint.sh
USER app
EXPOSE 8080 8081
ENTRYPOINT ["/app/docker-entrypoint.sh"]
