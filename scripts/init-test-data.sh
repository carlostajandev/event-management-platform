#!/bin/bash
set -e

echo "========================================="
echo "🚀 Inicializando datos de prueba"
echo "========================================="

# Configurar credenciales para AWS CLI
export AWS_ACCESS_KEY_ID=fakeMyKeyId
export AWS_SECRET_ACCESS_KEY=fakeSecretAccessKey
export AWS_DEFAULT_REGION=us-east-1

# 1. Esperar a que DynamoDB esté listo
echo "⏳ Esperando DynamoDB..."
until aws dynamodb list-tables --endpoint-url http://localhost:8000 2>/dev/null; do
  sleep 2
done
echo "✅ DynamoDB listo"

# 2. Inicializar tablas de prueba en DynamoDB
echo "📦 Creando tablas de prueba en DynamoDB..."
docker exec emp-dynamodb /docker-entrypoint-initdb.d/init-test-dynamodb.sh

# 3. Esperar a que LocalStack esté listo
echo "⏳ Esperando LocalStack..."
until docker exec emp-localstack curl -s http://localhost:4566/_localstack/health | grep -q '"sqs":"available"'; do
  sleep 2
done
echo "✅ LocalStack listo"

# 4. Inicializar colas de prueba en SQS
echo "📦 Creando colas de prueba en SQS..."
docker exec emp-localstack /etc/localstack/init/ready.d/init-test-sqs.sh

# 5. Verificar creación
echo "📋 Tablas DynamoDB de prueba:"
aws dynamodb list-tables --endpoint-url http://localhost:8000 | grep emp-test

echo "📋 Colas SQS de prueba:"
aws sqs list-queues --endpoint-url http://localhost:4566 | grep emp-test

echo "========================================="
echo "✅ Inicialización completada"
echo "========================================="