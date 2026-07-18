package com.mootmaker.tools.databaserepair;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import module java.base;

/**
 * Runs maintenance repairs directly against a deployed mootmaker-api environment's Cognito
 * user pool and DynamoDB tables. Unlike sample-data-generator, this bypasses the GraphQL API
 * entirely - what it needs to read and fix (the full list of Cognito users, the cognitoSub
 * linkage, the raw meeting-participants join table) isn't exposed there. Run via
 * {@code ./run.sh <environment> [--dry-run]} - see this project's README for details.
 */
public final class DatabaseRepair {

    private DatabaseRepair() {
    }

    public static void main(final String[] args) {
        final boolean dryRun = Arrays.asList(args).contains("--dry-run");

        final String userPoolId = requireEnv("COGNITO_USER_POOL_ID");
        final String peopleTableName = requireEnv("PEOPLE_TABLE_NAME");
        final String meetingsTableName = requireEnv("MEETINGS_TABLE_NAME");
        final String meetingParticipantsTableName = requireEnv("MEETING_PARTICIPANTS_TABLE_NAME");
        final Region region = Region.of(requireEnv("AWS_REGION"));

        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder().region(region).build();
                DynamoDbClient dynamoDbClient = DynamoDbClient.builder().region(region).build()) {

            runCreateMissingPersonsRepair(cognitoClient, dynamoDbClient, userPoolId, peopleTableName, dryRun);
            System.out.println();
            runRebuildMeetingParticipantsRepair(dynamoDbClient, meetingsTableName, meetingParticipantsTableName, dryRun);
        }
    }

    private static void runCreateMissingPersonsRepair(final CognitoIdentityProviderClient cognitoClient,
            final DynamoDbClient dynamoDbClient, final String userPoolId, final String peopleTableName, final boolean dryRun) {
        System.out.println("Repair: creating a Person for every confirmed Cognito user that doesn't have one"
                + (dryRun ? " (dry run - no changes will be made)" : "") + "...");

        final CreateMissingPersonsRepair.Result result =
                CreateMissingPersonsRepair.run(cognitoClient, dynamoDbClient, userPoolId, peopleTableName, dryRun);

        System.out.println("Done: " + result.repaired() + " Person record(s) " + (dryRun ? "would be " : "")
                + "created, " + result.alreadyLinked() + " user(s) already had one.");
    }

    private static void runRebuildMeetingParticipantsRepair(final DynamoDbClient dynamoDbClient, final String meetingsTableName,
            final String meetingParticipantsTableName, final boolean dryRun) {
        System.out.println("Repair: rebuilding meeting-participants from the meetings table"
                + (dryRun ? " (dry run - no changes will be made)" : "") + "...");

        final RebuildMeetingParticipantsRepair.Result result =
                RebuildMeetingParticipantsRepair.run(dynamoDbClient, meetingsTableName, meetingParticipantsTableName, dryRun);

        System.out.println("Done: " + result.created() + " participant row(s) " + (dryRun ? "would be " : "") + "created, "
                + result.removed() + " " + (dryRun ? "would be " : "") + "removed, " + result.alreadyCorrect() + " already correct.");
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
