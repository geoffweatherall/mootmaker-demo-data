package com.mootmaker.tools.databasereset;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import module java.base;

/**
 * Minimal in-memory test double covering only the two operations DatabaseReset uses (scan,
 * deleteItem). DatabaseReset issues its per-item deletes concurrently (see
 * DatabaseResetHandler.runInParallel), so every method here is synchronized - a real
 * DynamoDbClient handles concurrent calls from multiple threads fine, and this fake needs to
 * behave the same way. A single lock is fine (this is a test double, not something performance-
 * sensitive): it still exercises the production code's actual concurrency, just serialises the
 * fake's own bookkeeping.
 */
class FakeDynamoDbClient implements DynamoDbClient {

    final Map<String, List<Map<String, AttributeValue>>> tables = new HashMap<>();

    @Override
    public String serviceName() {
        return "dynamodb";
    }

    @Override
    public void close() {
    }

    @Override
    public synchronized ScanResponse scan(final ScanRequest request) {
        final List<Map<String, AttributeValue>> items = List.copyOf(tables.getOrDefault(request.tableName(), List.of()));
        return ScanResponse.builder().items(items).count(items.size()).build();
    }

    /** Matches on every attribute in key, not just "id" - meeting-participants is keyed by personId+sortKey. */
    @Override
    public synchronized DeleteItemResponse deleteItem(final DeleteItemRequest request) {
        final List<Map<String, AttributeValue>> items = tables.get(request.tableName());
        if (items != null) {
            items.removeIf(item -> request.key().entrySet().stream()
                    .allMatch(entry -> entry.getValue().equals(item.get(entry.getKey()))));
        }
        return DeleteItemResponse.builder().build();
    }
}
