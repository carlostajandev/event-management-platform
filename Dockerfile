# One image for both roles; SPRING_PROFILES_ACTIVE selects api or worker at runtime.

FROM eclipse-temurin:27_35-jdk-alpine AS build
WORKDIR /build
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/ticketing-platform-*.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:27_35-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "--enable-native-access=ALL-UNNAMED", "org.springframework.boot.loader.launch.JarLauncher"]
