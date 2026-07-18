package com.mootmaker.tools.databaserepair;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserStatusType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import module java.base;

/**
 * Repair #1: every confirmed Cognito user should have a linked Person, created automatically by
 * the API's {@code PostConfirmationCreatePersonHandler} on sign-up - but users created directly
 * (e.g. the demo user and e2e test user, both admin-created via Terraform rather than through
 * sign-up) skip that trigger entirely, and the trigger itself deliberately swallows its own
 * failures rather than retrying (see the API README's "Reset and real user accounts" section).
 * This finds every confirmed Cognito user with no Person linked via {@code cognitoSub} and creates
 * one, named after the part of their email before the "@" - a reasonable one-off backfill default,
 * not a re-implementation of the trigger's own behaviour (which names the Person from the Cognito
 * {@code name} attribute, unavailable here for users - like the demo/e2e ones - who never set one).
 */
final class CreateMissingPersonsRepair {

    private static final String COGNITO_SUB_INDEX = "cognitoSub-index";

    /** Cognito's maximum page size per ListUsers call. */
    private static final int LIST_USERS_PAGE_SIZE = 60;

    private CreateMissingPersonsRepair() {
    }

    record Result(int repaired, int alreadyLinked) {
    }

    static Result run(final CognitoIdentityProviderClient cognitoClient, final DynamoDbClient dynamoDbClient,
            final String userPoolId, final String peopleTableName, final boolean dryRun) {
        final List<UserType> users = listConfirmedUsers(cognitoClient, userPoolId);
        System.out.println("Found " + users.size() + " confirmed Cognito user(s).");

        int repaired = 0;
        int alreadyLinked = 0;
        for (final UserType user : users) {
            final String cognitoSub = requireAttribute(user, "sub");
            final String email = requireAttribute(user, "email");

            if (findPersonByCognitoSub(dynamoDbClient, peopleTableName, cognitoSub).isPresent()) {
                alreadyLinked++;
                continue;
            }

            final String name = emailLocalPart(email);
            System.out.println("  " + email + " -> creating Person '" + name + "'" + (dryRun ? " (dry run)" : ""));
            if (!dryRun) {
                final Person person = new Person(UUID.randomUUID().toString(), name, cognitoSub);
                dynamoDbClient.putItem(PutItemRequest.builder()
                        .tableName(peopleTableName)
                        .item(person.toItem())
                        .build());
            }
            repaired++;
        }
        return new Result(repaired, alreadyLinked);
    }

    /** The part of an email address before the "@"; the email itself if there's no "@". */
    static String emailLocalPart(final String email) {
        final int atIndex = email.indexOf('@');
        return atIndex >= 0 ? email.substring(0, atIndex) : email;
    }

    /**
     * Excludes UNCONFIRMED users: they haven't finished sign-up (never went through, and aren't
     * expected to have gone through, the PostConfirmation trigger), so it would be wrong to treat
     * them as needing a repair.
     */
    private static List<UserType> listConfirmedUsers(final CognitoIdentityProviderClient client, final String userPoolId) {
        final List<UserType> users = new ArrayList<>();
        String paginationToken = null;
        do {
            final ListUsersResponse response = client.listUsers(ListUsersRequest.builder()
                    .userPoolId(userPoolId)
                    .limit(LIST_USERS_PAGE_SIZE)
                    .paginationToken(paginationToken)
                    .build());
            response.users().stream()
                    .filter(user -> user.userStatus() != UserStatusType.UNCONFIRMED)
                    .forEach(users::add);
            paginationToken = response.paginationToken();
        } while (paginationToken != null && !paginationToken.isEmpty());
        return users;
    }

    private static Optional<String> findPersonByCognitoSub(
            final DynamoDbClient dynamoDbClient, final String tableName, final String cognitoSub) {
        final List<Map<String, AttributeValue>> items = dynamoDbClient.query(QueryRequest.builder()
                        .tableName(tableName)
                        .indexName(COGNITO_SUB_INDEX)
                        .keyConditionExpression("cognitoSub = :cognitoSub")
                        .expressionAttributeValues(Map.of(":cognitoSub", AttributeValue.builder().s(cognitoSub).build()))
                        .limit(1)
                        .build())
                .items();
        return items.isEmpty() ? Optional.empty() : Optional.of(items.getFirst().get("id").s());
    }

    private static String requireAttribute(final UserType user, final String attributeName) {
        return user.attributes().stream()
                .filter(attribute -> attributeName.equals(attribute.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Cognito user '" + user.username() + "' has no '" + attributeName + "' attribute"));
    }
}
