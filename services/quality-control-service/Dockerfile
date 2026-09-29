FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -ntp -pl services/quality-control-service -am package -DskipTests
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S conveyor && adduser -S conveyor -G conveyor && apk add --no-cache curl
WORKDIR /app
COPY --from=build /src/services/quality-control-service/target/quality-control-service-1.0.0.jar app.jar
USER conveyor
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
