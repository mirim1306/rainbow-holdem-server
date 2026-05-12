FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/rainbow-holdem-server.jar .
EXPOSE 10000
CMD ["java", "-jar", "rainbow-holdem-server.jar"]
