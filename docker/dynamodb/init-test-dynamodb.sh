#!/bin/sh
echo "🚀 Inicializando tablas DynamoDB para PRUEBAS..."

ENDPOINT="http://localhost:8000"

# Función para crear tabla
create_table() {
    local table_name="$1"
    local schema="$2"

    echo "Creando tabla $table_name..."
    curl -s -X POST "$ENDPOINT" \
        -H "Content-Type: application/x-amz-json-1.0" \
        -H "X-Amz-Target: DynamoDB_20120810.CreateTable" \
        -d "$schema" > /dev/null 2>&1

    if [ $? -eq 0 ]; then
        echo "  ✅ Tabla $table_name creada/existe"
    else
        echo "  ⚠️  Error al crear $table_name (puede que ya exista)"
    fi
}

# Tabla emp-test-events
create_table "emp-test-events" '{
    "TableName": "emp-test-events",
    "KeySchema": [{"AttributeName": "eventId", "KeyType": "HASH"}],
    "AttributeDefinitions": [{"AttributeName": "eventId", "AttributeType": "S"}],
    "BillingMode": "PAY_PER_REQUEST"
}'

# Tabla emp-test-tickets
create_table "emp-test-tickets" '{
    "TableName": "emp-test-tickets",
    "KeySchema": [{"AttributeName": "ticketId", "KeyType": "HASH"}],
    "AttributeDefinitions": [
        {"AttributeName": "ticketId", "AttributeType": "S"},
        {"AttributeName": "eventId", "AttributeType": "S"},
        {"AttributeName": "status", "AttributeType": "S"}
    ],
    "GlobalSecondaryIndexes": [{
        "IndexName": "eventId-status-index",
        "KeySchema": [
            {"AttributeName": "eventId", "KeyType": "HASH"},
            {"AttributeName": "status", "KeyType": "RANGE"}
        ],
        "Projection": {"ProjectionType": "ALL"}
    }],
    "BillingMode": "PAY_PER_REQUEST"
}'

# Tabla emp-test-orders
create_table "emp-test-orders" '{
    "TableName": "emp-test-orders",
    "KeySchema": [{"AttributeName": "orderId", "KeyType": "HASH"}],
    "AttributeDefinitions": [
        {"AttributeName": "orderId", "AttributeType": "S"},
        {"AttributeName": "userId", "AttributeType": "S"}
    ],
    "GlobalSecondaryIndexes": [{
        "IndexName": "userId-index",
        "KeySchema": [{"AttributeName": "userId", "KeyType": "HASH"}],
        "Projection": {"ProjectionType": "ALL"}
    }],
    "BillingMode": "PAY_PER_REQUEST"
}'

# Tabla emp-test-idempotency
create_table "emp-test-idempotency" '{
    "TableName": "emp-test-idempotency",
    "KeySchema": [{"AttributeName": "idempotencyKey", "KeyType": "HASH"}],
    "AttributeDefinitions": [{"AttributeName": "idempotencyKey", "AttributeType": "S"}],
    "BillingMode": "PAY_PER_REQUEST"
}'

# Tabla emp-test-audit
create_table "emp-test-audit" '{
    "TableName": "emp-test-audit",
    "KeySchema": [
        {"AttributeName": "entityId", "KeyType": "HASH"},
        {"AttributeName": "timestamp", "KeyType": "RANGE"}
    ],
    "AttributeDefinitions": [
        {"AttributeName": "entityId", "AttributeType": "S"},
        {"AttributeName": "timestamp", "AttributeType": "S"}
    ],
    "BillingMode": "PAY_PER_REQUEST"
}'

echo "✅ Inicialización de tablas completada"s