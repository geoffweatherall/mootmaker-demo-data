package com.roombooking.tools.databaserepair;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import module java.base;

/** Minimal in-memory test double covering only the operations CreateMissingPersonsRepair uses. */
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
    public PutItemResponse putItem(final PutItemRequest request) {
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
    public QueryResponse query(final QueryRequest request) {
        final String[] parts = request.keyConditionExpression().split("=", 2);
        final String attributeName = parts[0].trim();
        final AttributeValue attributeValue = request.expressionAttributeValues().get(parts[1].trim());

        final List<Map<String, AttributeValue>> items = tables.getOrDefault(request.tableName(), new ArrayList<>()).stream()
                .filter(item -> attributeValue.equals(item.get(attributeName)))
                .toList();
        return QueryResponse.builder().items(items).count(items.size()).build();
    }
}
