#!/bin/bash
set -e

echo "========================================="
echo "Running tests — Event Management Platform v2"
echo "========================================="

# Verify Java 25
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
echo "Java: $JAVA_VERSION"

if [[ ! "$JAVA_VERSION" =~ ^25 ]]; then
    echo "ERROR: Java 25 required. Current: $JAVA_VERSION"
    echo "Install: sdk install java 25.0.2-tem"
    exit 1
fi

# Start LocalStack (DynamoDB + SQS)
echo "Starting LocalStack..."
docker compose -f infrastructure/docker-compose.yml up localstack -d

echo -n "Waiting for LocalStack..."
until curl -s http://localhost:4566/_localstack/health | grep -q '"dynamodb":"available"'; do
    echo -n "."
    sleep 2
done
echo " ready"

# Initialize DynamoDB tables and SQS queues
echo "Initializing AWS resources..."
bash infrastructure/localstack/init-aws.sh

# Build shared modules first (required by all services)
echo "Building shared modules..."
./mvnw install -pl shared/domain,shared/infrastructure -DskipTests --no-transfer-progress

# Run tests for all services (parallel via Maven)
echo "Running tests — all 4 services..."
./mvnw test -pl services/event-service,services/reservation-service,services/order-service,services/consumer-service \
    --no-transfer-progress \
    -Dspring.profiles.active=test

# JaCoCo coverage verification
echo "Checking coverage gates (line >= 90%, branch >= 85%)..."
./mvnw verify -pl services/event-service,services/reservation-service,services/order-service,services/consumer-service \
    --no-transfer-progress \
    -DskipTests=true

echo "========================================="
echo "All tests passed"
echo "========================================="
