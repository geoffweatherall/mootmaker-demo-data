package com.mootmaker.tools.databaserepair;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import module java.base;

/**
 * Lambda entry point for the database-repair tool. Runs maintenance repairs directly against a
 * deployed mootmaker-api environment's Cognito user pool and DynamoDB tables. Unlike
 * sample-data-generator, this bypasses the GraphQL API entirely - what it needs to read and fix
 * (the full list of Cognito users, the cognitoSub linkage, the raw meeting-participants join
 * table) isn't exposed there. Invoked on demand via {@code ./run.sh <environment> [--dry-run]}
 * (see this project's README); {@code event}'s {@code "dryRun"} boolean field controls whether
 * either repair actually writes anything.
 *
 * <p>The two repairs touch entirely different tables (People vs. Meetings/meeting-participants),
 * so they run concurrently on their own threads rather than one after the other, and each
 * repair's own per-item AWS calls (one check-and-create per Cognito user; one put/delete per
 * meeting-participants row) are themselves spread across a bounded pool via
 * {@link #runInParallel}. Both are what keep a run comfortably inside a Lambda invocation's
 * 15-minute hard ceiling as the number of users/meetings grows, since neither repair's per-item
 * work depends on any other item's result.
 */
public final class DatabaseRepairHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    /**
     * Bounded concurrency for each repair's independent per-item AWS calls. Deliberately modest -
     * matches sample-data-generator's MAX_CONCURRENT_REQUESTS reasoning: enough to meaningfully
     * speed up a run without throwing an unnecessary burst of requests at DynamoDB/Cognito.
     */
    static final int MAX_CONCURRENT_REQUESTS = 8;

    @Override
    public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
        final boolean dryRun = event != null && Boolean.TRUE.equals(event.get("dryRun"));

        final String userPoolId = requireEnv("COGNITO_USER_POOL_ID");
        final String peopleTableName = requireEnv("PEOPLE_TABLE_NAME");
        final String meetingsTableName = requireEnv("MEETINGS_TABLE_NAME");
        final String meetingParticipantsTableName = requireEnv("MEETING_PARTICIPANTS_TABLE_NAME");
        final Region region = Region.of(requireEnv("AWS_REGION"));

        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder().region(region).build();
                DynamoDbClient dynamoDbClient = DynamoDbClient.builder().region(region).build()) {

            final ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                final Future<CreateMissingPersonsRepair.Result> missingPersonsFuture = executor.submit(() ->
                        runCreateMissingPersonsRepair(cognitoClient, dynamoDbClient, userPoolId, peopleTableName, dryRun));
                final Future<RebuildMeetingParticipantsRepair.Result> participantsFuture = executor.submit(() ->
                        runRebuildMeetingParticipantsRepair(dynamoDbClient, meetingsTableName, meetingParticipantsTableName, dryRun));

                final CreateMissingPersonsRepair.Result missingPersonsResult = getResult(missingPersonsFuture);
                final RebuildMeetingParticipantsRepair.Result participantsResult = getResult(participantsFuture);

                final Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("dryRun", dryRun);
                summary.put("personsCreated", missingPersonsResult.repaired());
                summary.put("personsAlreadyLinked", missingPersonsResult.alreadyLinked());
                summary.put("participantRowsCreated", participantsResult.created());
                summary.put("participantRowsRemoved", participantsResult.removed());
                summary.put("participantRowsAlreadyCorrect", participantsResult.alreadyCorrect());
                return summary;
            } finally {
                executor.shutdown();
            }
        }
    }

    private static CreateMissingPersonsRepair.Result runCreateMissingPersonsRepair(
            final CognitoIdentityProviderClient cognitoClient, final DynamoDbClient dynamoDbClient,
            final String userPoolId, final String peopleTableName, final boolean dryRun) {
        System.out.println("Repair: creating a Person for every confirmed Cognito user that doesn't have one"
                + (dryRun ? " (dry run - no changes will be made)" : "") + "...");

        final CreateMissingPersonsRepair.Result result =
                CreateMissingPersonsRepair.run(cognitoClient, dynamoDbClient, userPoolId, peopleTableName, dryRun);

        System.out.println("Done: " + result.repaired() + " Person record(s) " + (dryRun ? "would be " : "")
                + "created, " + result.alreadyLinked() + " user(s) already had one.");
        return result;
    }

    private static RebuildMeetingParticipantsRepair.Result runRebuildMeetingParticipantsRepair(
            final DynamoDbClient dynamoDbClient, final String meetingsTableName,
            final String meetingParticipantsTableName, final boolean dryRun) {
        System.out.println("Repair: rebuilding meeting-participants from the meetings table"
                + (dryRun ? " (dry run - no changes will be made)" : "") + "...");

        final RebuildMeetingParticipantsRepair.Result result =
                RebuildMeetingParticipantsRepair.run(dynamoDbClient, meetingsTableName, meetingParticipantsTableName, dryRun);

        System.out.println("Done: " + result.created() + " participant row(s) " + (dryRun ? "would be " : "") + "created, "
                + result.removed() + " " + (dryRun ? "would be " : "") + "removed, " + result.alreadyCorrect() + " already correct.");
        return result;
    }

    private static <T> T getResult(final Future<T> future) {
        try {
            return future.get();
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(cause);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a repair to finish", e);
        }
    }

    private static String requireEnv(final String name) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " environment variable is required. This function must be deployed via ./deploy.sh, which sets it.");
        }
        return value;
    }

    /**
     * Runs {@code action} for every item, on a bounded pool of {@value #MAX_CONCURRENT_REQUESTS}
     * threads, and waits for them all to finish. The first failure is rethrown after every task
     * has completed, same as a sequential loop would have failed on the first bad item - just not
     * necessarily the same item, since order isn't guaranteed under parallel execution.
     * Package-private (rather than private) so {@link CreateMissingPersonsRepair}, {@link
     * RebuildMeetingParticipantsRepair}, and this class's own tests can use/exercise it directly.
     */
    static <T> void runInParallel(final List<T> items, final Consumer<T> action) {
        if (items.isEmpty()) {
            return;
        }
        final ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_CONCURRENT_REQUESTS, items.size()));
        try {
            final List<Future<?>> futures = new ArrayList<>(items.size());
            for (final T item : items) {
                futures.add(executor.submit(() -> action.accept(item)));
            }
            RuntimeException firstFailure = null;
            for (final Future<?> future : futures) {
                try {
                    future.get();
                } catch (final ExecutionException e) {
                    if (firstFailure == null) {
                        final Throwable cause = e.getCause();
                        firstFailure = cause instanceof RuntimeException runtimeException
                                ? runtimeException
                                : new IllegalStateException(cause);
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for parallel tasks to finish", e);
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        } finally {
            executor.shutdown();
        }
    }
}
