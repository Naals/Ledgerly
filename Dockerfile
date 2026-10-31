# One Dockerfile for every service:  docker build --build-arg MODULE=ledger-core .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /workspace
COPY pom.xml .
COPY ledger-core ledger-core
COPY payment-service payment-service
RUN mvn -q -B -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /workspace/${MODULE}/target/${MODULE}-*.jar app.jar
RUN useradd --system app && chown app /app
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]