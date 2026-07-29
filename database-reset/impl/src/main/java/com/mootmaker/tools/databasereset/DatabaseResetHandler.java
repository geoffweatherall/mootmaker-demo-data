package com.mootmaker.tools.databasereset;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import module java.base;

/**
 * Lambda entry point for the database-reset tool (see {@link DatabaseReset} for what it actually
 * deletes and why). Invoked on demand via {@code ./run.sh <environment>} (see this project's
 * README) - most often by a developer resetting a sandbox, or by sample-data-generator as the
 * first step of repopulating an environment (see its {@code DatabaseResetInvoker}). The input
 * payload is unused - there's nothing to configure per invocation.
 *
 * <p>Rooms, unlinked people, and meetings-plus-participants live in different tables and don't
 * depend on each other, so the three deletion passes below run concurrently on their own threads;
 * each pass's own per-item {@code DeleteItem} calls are themselves spread across a bounded pool
 * (see {@link #runInParallel}). Together, this is what keeps a run comfortably inside a Lambda
 * invocation's 15-minute hard ceiling as stored data volume grows, rather than the function's
 * configured timeout doing that work.
 */
public final class DatabaseResetHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    /**
     * Bounded concurrency for each pass's independent per-item {@code DeleteItem} calls.
     * Deliberately modest - matches the other mootmaker-tools Lambdas' same constant and
     * reasoning: enough to meaningfully speed up a run without throwing an unnecessary burst of
     * requests at DynamoDB.
     */
    static final int MAX_CONCURRENT_REQUESTS = 8;

    @Override
    public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
        final String roomsTableName = requireEnv("ROOMS_TABLE_NAME");
        final String peopleTableName = requireEnv("PEOPLE_TABLE_NAME");
        final String meetingsTableName = requireEnv("MEETINGS_TABLE_NAME");
        final String meetingParticipantsTableName = requireEnv("MEETING_PARTICIPANTS_TABLE_NAME");
        final Region region = Region.of(requireEnv("AWS_REGION"));

        try (DynamoDbClient dynamoDbClient = DynamoDbClient.builder().region(region).build()) {
            final ExecutorService executor = Executors.newFixedThreadPool(3);
            try {
                final Future<Integer> roomsFuture = executor.submit(() -> DatabaseReset.deleteAllItems(dynamoDbClient, roomsTableName));
                final Future<Integer> peopleFuture =
                        executor.submit(() -> DatabaseReset.deleteUnlinkedPeople(dynamoDbClient, peopleTableName));
                final Future<Integer> meetingsFuture = executor.submit(() -> DatabaseReset.deleteAllMeetingsAndParticipants(
                        dynamoDbClient, meetingsTableName, meetingParticipantsTableName));

                final int roomsDeleted = getResult(roomsFuture);
                final int peopleDeleted = getResult(peopleFuture);
                final int meetingsDeleted = getResult(meetingsFuture);

                System.out.println("Deleted " + roomsDeleted + " room(s), " + peopleDeleted
                        + " unlinked person(s), " + meetingsDeleted + " meeting(s) (and their participant rows).");

                final Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("roomsDeleted", roomsDeleted);
                summary.put("peopleDeleted", peopleDeleted);
                summary.put("meetingsDeleted", meetingsDeleted);
                return summary;
            } finally {
                executor.shutdown();
            }
        }
    }

    private static <T> T getResult(final Future<T> future) {
        try {
            return future.get();
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(cause);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a deletion pass to finish", e);
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
     * Package-private (rather than private) so {@link DatabaseReset} and this class's own tests
     * can use/exercise it directly. Identical to the other mootmaker-tools Lambdas' own copy of
     * this helper - see sample-data-generator's {@code SampleDataGenerator.runInParallel} for the
     * original.
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
