# ════════════════════════════════════════════════════════════════
# Multi-stage build — matches Java 25 declared in pom.xml
# Stage 1: build fat JAR
# Stage 2: minimal JRE runtime (non-root user)
# ════════════════════════════════════════════════════════════════
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and parent POM first (layer cache)
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn/
COPY pom.xml ./
COPY shared/domain/pom.xml shared/domain/pom.xml
COPY shared/infrastructure/pom.xml shared/infrastructure/pom.xml
COPY services/event-service/pom.xml services/event-service/pom.xml
COPY services/reservation-service/pom.xml services/reservation-service/pom.xml
COPY services/order-service/pom.xml services/order-service/pom.xml
COPY services/consumer-service/pom.xml services/consumer-service/pom.xml

# Download dependencies (cached unless pom.xml changes)
RUN chmod +x mvnw && \
    ./mvnw dependency:go-offline -pl shared/domain,shared/infrastructure,services/event-service \
    --no-transfer-progress -q

# Copy source
COPY shared/domain/src shared/domain/src/
COPY shared/infrastructure/src shared/infrastructure/src/
COPY services/event-service/src services/event-service/src/

# Build (skip tests — tests run in CI)
RUN ./mvnw package -pl shared/domain,shared/infrastructure,services/event-service \
    -am --no-transfer-progress -DskipTests -q

# ────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine AS runtime

# Security: non-root user
RUN addgroup -S emp && adduser -S emp -G emp
USER emp

WORKDIR /app
COPY --from=builder /build/services/event-service/target/*.jar app.jar

# Health check uses Actuator /health endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health || exit 1

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
