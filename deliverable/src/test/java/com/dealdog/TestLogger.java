package com.dealdog;

import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logs every test method to the console as it runs, so `mvn clean test` shows what was
 * actually exercised rather than a single per-class summary line.
 *
 * Output (ASCII only, so it renders under any console encoding):
 *   > RUN  [11] invariantI3_byteIdenticalReplayChangesNothing
 *       PASS  (412 ms)
 */
public class TestLogger implements BeforeTestExecutionCallback, TestWatcher {

    private static final ThreadLocal<Long> START = new ThreadLocal<>();
    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();
    private static final AtomicInteger SKIPPED = new AtomicInteger();
    private static final AtomicInteger ORDER = new AtomicInteger();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            int p = PASSED.get(), f = FAILED.get(), s = SKIPPED.get();
            if (p + f + s == 0) return;
            System.out.println();
            System.out.println("----------------------------------------------------------");
            System.out.printf(" DealDog test methods: %d passed, %d failed, %d skipped%n", p, f, s);
            System.out.println("----------------------------------------------------------");
        }));
    }

    private static String name(ExtensionContext ctx) {
        return ctx.getTestMethod().map(java.lang.reflect.Method::getName).orElse(ctx.getDisplayName());
    }

    private static long elapsed() {
        Long t = START.get();
        return t == null ? 0L : System.currentTimeMillis() - t;
    }

    @Override
    public void beforeTestExecution(ExtensionContext ctx) {
        START.set(System.currentTimeMillis());
        System.out.printf("%n> RUN  [%02d] %s%n", ORDER.incrementAndGet(), name(ctx));
    }

    @Override
    public void testSuccessful(ExtensionContext ctx) {
        PASSED.incrementAndGet();
        System.out.printf("    PASS  (%d ms)%n", elapsed());
    }

    @Override
    public void testFailed(ExtensionContext ctx, Throwable cause) {
        FAILED.incrementAndGet();
        System.out.printf("    FAIL  (%d ms)  %s: %s%n", elapsed(),
                cause.getClass().getSimpleName(), cause.getMessage());
    }

    @Override
    public void testAborted(ExtensionContext ctx, Throwable cause) {
        SKIPPED.incrementAndGet();
        System.out.printf("    ABORTED  %s%n", cause == null ? "" : cause.getMessage());
    }

    @Override
    public void testDisabled(ExtensionContext ctx, Optional<String> reason) {
        SKIPPED.incrementAndGet();
        System.out.printf("    DISABLED  %s%n", reason.orElse(""));
    }
}
