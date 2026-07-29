package com.mootmaker.tools.databaserepair;

import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code DatabaseRepairHandler.runInParallel}, the bounded-concurrency helper both repairs
 * use to speed up their per-item AWS calls. Mirrors sample-data-generator's
 * SampleDataGeneratorConcurrencyTest, which covers the same pattern there.
 */
class DatabaseRepairHandlerConcurrencyTest {

    @Test
    void processesEveryItemExactlyOnce() {
        final List<Integer> items = IntStream.range(0, 50).boxed().toList();
        final Set<Integer> seen = ConcurrentHashMap.newKeySet();

        DatabaseRepairHandler.runInParallel(items, seen::add);

        assertEquals(new HashSet<>(items), seen);
    }

    @Test
    void actuallyRunsTasksConcurrentlyRatherThanOneAtATime() {
        final List<Integer> items = IntStream.range(0, 20).boxed().toList();
        final AtomicInteger current = new AtomicInteger();
        final AtomicInteger maxObserved = new AtomicInteger();

        DatabaseRepairHandler.runInParallel(items, item -> {
            final int nowRunning = current.incrementAndGet();
            maxObserved.updateAndGet(max -> Math.max(max, nowRunning));
            try {
                Thread.sleep(20);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            current.decrementAndGet();
        });

        assertTrue(maxObserved.get() > 1,
                "expected more than one task to run at the same time, got max concurrent = " + maxObserved.get());
    }

    @Test
    void propagatesAFailureOnlyAfterEveryTaskHasCompleted() {
        final List<Integer> items = List.of(1, 2, 3);
        final AtomicInteger completed = new AtomicInteger();

        final RuntimeException thrown = assertThrows(RuntimeException.class, () -> DatabaseRepairHandler.runInParallel(items, item -> {
            completed.incrementAndGet();
            if (item == 2) {
                throw new IllegalStateException("boom");
            }
        }));

        assertEquals(3, completed.get(), "every task should still run even though one of them fails");
        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void doesNothingForAnEmptyList() {
        DatabaseRepairHandler.runInParallel(List.of(), item -> {
            throw new AssertionError("action should never be called for an empty list");
        });
    }
}
