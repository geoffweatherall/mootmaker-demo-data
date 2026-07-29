package com.mootmaker.tools.databaserepair;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import module java.base;

/**
 * Minimal in-memory test double covering only the operations the repairs use. Both repairs now
 * issue their per-item put/query/delete calls concurrently (see
 * DatabaseRepairHandler.runInParallel), so every method here is synchronized - a real
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
    public synchronized PutItemResponse putItem(final PutItemRequest request) {
        tables.computeIfAbsent(request.tableName(), _ -> new ArrayList<>()).add(request.item());
        return PutItemResponse.builder().build();
    }

    /**
     * Supports only the single-equality-condition query ({@code "cognitoSub = :cognitoSub"})
     * CreateMissingPersonsRepair issues against a GSI; matches by scanning the table's items
     * rather than modelling indexes, since the fake only needs to behave the same as the real
     * query, not perform like it.
     */
    @Override
    public synchronized QueryResponse query(final QueryRequest request) {
        final String[] parts = request.keyConditionExpression().split("=", 2);
        final String attributeName = parts[0].trim();
        final AttributeValue attributeValue = request.expressionAttributeValues().get(parts[1].trim());

        final List<Map<String, AttributeValue>> items = tables.getOrDefault(request.tableName(), List.of()).stream()
                .filter(item -> attributeValue.equals(item.get(attributeName)))
                .toList();
        return QueryResponse.builder().items(items).count(items.size()).build();
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
