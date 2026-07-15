package com.roombooking.tools.databaserepair;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

/**
 * Mirrors just enough of room-booking-api's Person model to write a compatible DynamoDB item:
 * {@code id}, {@code name}, and {@code cognitoSub} (the Cognito user's {@code sub}, which links
 * this Person back to their account - see the API's {@code PersonRepository} /
 * {@code cognitoSub-index} GSI). Deliberately duplicated rather than shared - this is a separate
 * Maven project with no shared-code mechanism, the same reasoning sample-data-generator's
 * GraphQlClient documents for why it re-implements rather than depends on the API project.
 */
record Person(String id, String name, String cognitoSub) {

    Map<String, AttributeValue> toItem() {
        return Map.of(
                "id", AttributeValue.builder().s(id).build(),
                "name", AttributeValue.builder().s(name).build(),
                "cognitoSub", AttributeValue.builder().s(cognitoSub).build());
    }
}
