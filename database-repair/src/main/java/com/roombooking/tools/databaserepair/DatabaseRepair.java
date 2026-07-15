package com.roombooking.tools.databaserepair;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import module java.base;

/**
 * Runs maintenance repairs directly against a deployed room-booking-api environment's Cognito
 * user pool and DynamoDB tables. Unlike sample-data-generator, this bypasses the GraphQL API
 * entirely - what it needs to read and fix (the full list of Cognito users, the cognitoSub
 * linkage) isn't exposed there. Run via {@code ./run.sh <environment> [--dry-run]} - see this
 * project's README for details. New repairs are expected to be added here over time; this is
 * currently just the one.
 */
public final class DatabaseRepair {

    private DatabaseRepair() {
    }

    public static void main(final String[] args) {
        final boolean dryRun = Arrays.asList(args).contains("--dry-run");

        final String userPoolId = requireEnv("COGNITO_USER_POOL_ID");
        final String peopleTableName = requireEnv("PEOPLE_TABLE_NAME");
        final Region region = Region.of(requireEnv("AWS_REGION"));

        System.out.println("Repair: creating a Person for every confirmed Cognito user that doesn't have one"
                + (dryRun ? " (dry run - no changes will be made)" : "") + "...");

        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder().region(region).build();
                DynamoDbClient dynamoDbClient = DynamoDbClient.builder().region(region).build()) {

            final CreateMissingPersonsRepair.Result result =
                    CreateMissingPersonsRepair.run(cognitoClient, dynamoDbClient, userPoolId, peopleTableName, dryRun);

            System.out.println();
            System.out.println("Done: " + result.repaired() + " Person record(s) " + (dryRun ? "would be " : "")
                    + "created, " + result.alreadyLinked() + " user(s) already had one.");
        }
    }

    private static String requireEnv(final String name) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " environment variable is required. Run this tool via ./run.sh <environment>.");
        }
        return value;
    }
}
