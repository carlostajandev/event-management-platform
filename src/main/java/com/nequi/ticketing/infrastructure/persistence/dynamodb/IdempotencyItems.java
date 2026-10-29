package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.PK;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.instant;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.s;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.string;

/** Idempotency item: PK=IDEMPOTENCY#scope#key, removed by TTL after 24 hours. */
final class IdempotencyItems {

    static final Duration RETENTION = Duration.ofHours(24);

    private IdempotencyItems() {
    }

    static Map<String, AttributeValue> key(IdempotencyKey key) {
        return Map.of(PK, s("IDEMPOTENCY#" + key.scope() + "#" + key.value()));
    }

    /** Put that fails if the key was already used; written in the same transaction as the operation. */
    static TransactWriteItem create(String table, IdempotencyRecord record) {
        Map<String, AttributeValue> item = new HashMap<>(key(record.key()));
        item.put("scope", s(record.key().scope()));
        item.put("idempotencyKey", s(record.key().value()));
        item.put("requestHash", s(record.requestHash()));
        item.put("resourceId", s(record.resourceId()));
        item.put("createdAt", millis(record.createdAt()));
        item.put("ttl", n(record.createdAt().plus(RETENTION).getEpochSecond()));
        return TransactWriteItem.builder().put(put -> put
                .tableName(table)
                .item(item)
                .conditionExpression("attribute_not_exists(PK)"))
                .build();
    }

    static IdempotencyRecord fromItem(Map<String, AttributeValue> item) {
        return new IdempotencyRecord(
                new IdempotencyKey(string(item, "scope"), string(item, "idempotencyKey")),
                string(item, "requestHash"),
                string(item, "resourceId"),
                instant(item, "createdAt"));
    }
}
