#!/bin/bash
set -e

echo "========================================="
echo "🚀 Preparando entorno para pruebas con Java 25"
echo "========================================="

# Verificar versión de Java
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
echo "📌 Versión de Java detectada: $JAVA_VERSION"

if [[ ! "$JAVA_VERSION" =~ ^25 ]]; then
    echo "❌ Se requiere Java 25. Versión actual: $JAVA_VERSION"
    echo ""
    echo "📋 Para instalar Java 25:"
    echo "   Usando SDKMAN: sdk install java 25.0.2-tem"
    echo "   Usando apt: sudo apt install temurin-25-jdk"
    exit 1
fi

# Mostrar JAVA_HOME
if [ -n "$JAVA_HOME" ]; then
    echo "📌 JAVA_HOME: $JAVA_HOME"
else
    echo "⚠️  JAVA_HOME no está configurado"
fi

# 1. Limpiar contenedores huérfanos
echo "🧹 Limpiando contenedores huérfanos..."
docker-compose down --remove-orphans

# 2. Levantar contenedores
echo "📦 Levantando contenedores Docker..."
docker-compose up -d dynamodb-local localstack

# 3. Esperar a que estén saludables
echo "⏳ Esperando a que los contenedores estén listos..."
sleep 5

# Verificar DynamoDB
echo -n "⏳ Esperando DynamoDB..."
until curl -s http://localhost:8000 > /dev/null; do
    echo -n "."
    sleep 2
done
echo " ✅"

# Verificar LocalStack
echo -n "⏳ Esperando LocalStack..."
until docker exec emp-localstack curl -s http://localhost:4566/_localstack/health | grep -q '"sqs":"available"'; do
    echo -n "."
    sleep 2
done
echo " ✅"

# 4. Inicializar datos de prueba
echo "🔧 Inicializando datos de prueba..."
./scripts/init-test-data.sh

# 5. Limpiar compilación anterior
echo "🧹 Limpiando compilación anterior..."
./mvnw clean

# 6. Compilar con Java 25
echo "🏗️  Compilando con Java 25..."
./mvnw compile

# 7. Ejecutar pruebas
echo "🏃 Ejecutando pruebas con Java 25..."
./mvnw verify

echo "========================================="
echo "✅ Proceso completado con Java 25"
echo "========================================="