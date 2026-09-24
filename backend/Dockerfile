FROM node:22-bookworm-slim AS frontend-build
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /build
COPY backend/ backend/
RUN mkdir -p backend/src/main/resources/static/assets backend/src/main/resources/vite
COPY --from=frontend-build /build/frontend/dist/assets/ backend/src/main/resources/static/assets/
COPY --from=frontend-build /build/frontend/dist/manifest.json backend/src/main/resources/vite/manifest.json
RUN mvn -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=backend-build /build/backend/target/pokemon-collection-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-jar", "/app/app.jar"]
