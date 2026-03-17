FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /build
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn/
COPY pom.xml ./
COPY shared/domain/pom.xml shared/domain/pom.xml
COPY shared/infrastructure/pom.xml shared/infrastructure/pom.xml
COPY services/event-service/pom.xml services/event-service/pom.xml
COPY services/reservation-service/pom.xml services/reservation-service/pom.xml
COPY services/order-service/pom.xml services/order-service/pom.xml
COPY services/consumer-service/pom.xml services/consumer-service/pom.xml
RUN chmod +x mvnw && \
    ./mvnw dependency:go-offline -pl shared/domain,shared/infrastructure,services/consumer-service \
    --no-transfer-progress -q
COPY shared/domain/src shared/domain/src/
COPY shared/infrastructure/src shared/infrastructure/src/
COPY services/consumer-service/src services/consumer-service/src/
RUN ./mvnw package -pl shared/domain,shared/infrastructure,services/consumer-service \
    -am --no-transfer-progress -DskipTests -q

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S emp && adduser -S emp -G emp
USER emp
WORKDIR /app
COPY --from=builder /build/services/consumer-service/target/*.jar app.jar
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8084/actuator/health || exit 1
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
