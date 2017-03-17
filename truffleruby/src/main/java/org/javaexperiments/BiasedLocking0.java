package org.javaexperiments;

public class BiasedLocking0 {

    private static final int LOOP_COUNT = 10000000; // 10 million

    public static void main(final String[] args) {
        incrementCounter();
    }

    public static void incrementCounter() {
        final long startTime = System.currentTimeMillis();

        final Counter counter = new Counter();

        for (int r = 0; r < 100; r++) {
            for (int i = 0; i < LOOP_COUNT; i++) {
                counter.increment();
            }
        }

        final long endTime = System.currentTimeMillis();

        System.out.printf("Counter: %,d - Elapsed time: %d ms\n",
                counter.getCount(), (endTime - startTime));
    }

    private static class Counter {
        private long count;

        public synchronized void increment() {
            count += 1;
        }

        public long getCount() {
            return count;
        }
    }
}