package com.ags.transactions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks on separate threads, released at the same instant by a latch, so they really
 * overlap instead of running one after another as they're submitted.
 */
public final class Concurrently {

    /** What one task produced: a value or the exception it threw. */
    public record Outcome<T>(T value, Throwable error) {
        public boolean succeeded() {
            return error == null;
        }
    }

    private Concurrently() {
    }

    @SafeVarargs
    public static <T> List<Outcome<T>> run(Callable<T>... tasks) throws InterruptedException {
        return run(List.of(tasks));
    }

    public static <T> List<Outcome<T>> run(List<Callable<T>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(new Outcome<>(future.get(30, TimeUnit.SECONDS), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome<>(null, e.getCause()));
                } catch (java.util.concurrent.TimeoutException e) {
                    outcomes.add(new Outcome<>(null, e));
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
