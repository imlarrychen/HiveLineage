package com.panfan.hivelineage.v2.testing;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * Reports assumption skips explicitly when running legacy and semantic engine suites together.
 */
public final class ValidationRunner {
    private ValidationRunner() {
    }

    public static void main(String[] args) throws Exception {
        Class<?>[] classes = new Class<?>[args.length];
        for (int index = 0; index < args.length; index++) {
            classes[index] = Class.forName(args[index]);
        }
        Statistics statistics = run(true, classes);
        for (Failure failure : statistics.getResult().getFailures()) {
            System.out.println(failure.getTestHeader());
            System.out.println(failure.getTrace());
        }
        System.out.println(statistics.summary());
        if (!statistics.getResult().wasSuccessful()) {
            System.exit(1);
        }
    }

    static Statistics run(Class<?>... classes) {
        return run(false, classes);
    }

    private static Statistics run(boolean logSkips, Class<?>... classes) {
        AtomicInteger passed = new AtomicInteger();
        AtomicInteger assumptions = new AtomicInteger();
        AtomicInteger classAssumptions = new AtomicInteger();
        AtomicInteger classFailures = new AtomicInteger();
        ConcurrentMap<Description, TestState> running = new ConcurrentHashMap<>();
        JUnitCore junit = new JUnitCore();
        junit.addListener(new RunListener() {
            @Override
            public void testStarted(Description description) {
                running.put(description, new TestState());
            }

            @Override
            public void testFailure(Failure failure) {
                TestState state = running.get(failure.getDescription());
                if (state == null) {
                    classFailures.incrementAndGet();
                } else {
                    state.failed = true;
                }
            }

            @Override
            public void testAssumptionFailure(Failure failure) {
                TestState state = running.get(failure.getDescription());
                if (state == null) {
                    classAssumptions.incrementAndGet();
                    if (logSkips) {
                        System.out.println("CLASS ASSUMPTION SKIP: " + failure.getTestHeader());
                    }
                } else {
                    if (!state.skipped) {
                        assumptions.incrementAndGet();
                    }
                    state.skipped = true;
                    if (logSkips) {
                        System.out.println("ASSUMPTION SKIP: " + failure.getTestHeader());
                    }
                }
            }

            @Override
            public void testFinished(Description description) {
                TestState state = running.remove(description);
                if (state != null && !state.failed && !state.skipped) {
                    passed.incrementAndGet();
                }
            }
        });
        Result result = junit.run(classes);
        return new Statistics(result, passed.get(), assumptions.get(), classAssumptions.get(), classFailures.get());
    }

    static final class Statistics {
        private final Result result;
        private final int passed;
        private final int assumptionSkips;
        private final int classAssumptionSkips;
        private final int classFailures;

        private Statistics(Result result, int passed, int assumptionSkips, int classAssumptionSkips, int classFailures) {
            this.result = result;
            this.passed = passed;
            this.assumptionSkips = assumptionSkips;
            this.classAssumptionSkips = classAssumptionSkips;
            this.classFailures = classFailures;
        }

        Result getResult() {
            return result;
        }

        int getPassed() {
            return passed;
        }

        int getAssumptionSkips() {
            return assumptionSkips;
        }

        int getClassAssumptionSkips() {
            return classAssumptionSkips;
        }

        int getClassFailures() {
            return classFailures;
        }

        String summary() {
            return "Tests=" + result.getRunCount() + ", passed=" + passed + ", failures=" + result.getFailureCount()
                    + ", assumptionSkips=" + assumptionSkips + ", classAssumptionSkips=" + classAssumptionSkips
                    + ", classFailures=" + classFailures + ", ignored=" + result.getIgnoreCount();
        }
    }

    private static final class TestState {
        private volatile boolean failed;
        private volatile boolean skipped;
    }
}
