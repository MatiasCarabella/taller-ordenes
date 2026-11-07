FROM eclipse-temurin:25-jdk-jammy@sha256:e4f029273ed9e4444697df30bb6098dbe51477291598aebc54dd0b48d95d6ae7 AS build
WORKDIR /build
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN chmod +x mvnw
COPY backend/src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package
COPY infra/Healthcheck.java /health/Healthcheck.java
RUN javac -d /health /health/Healthcheck.java

FROM eclipse-temurin:25-jre-jammy@sha256:659b528cca74a0edc5c25315ec5819f35bce40bbcec645c4c534987e4c6be7c1
RUN groupadd --gid 10001 taller && useradd --uid 10001 --gid taller --no-create-home taller
WORKDIR /app
COPY --from=build /build/target/backend-0.1.0.jar app.jar
COPY --from=build /health/Healthcheck.class health/Healthcheck.class
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
