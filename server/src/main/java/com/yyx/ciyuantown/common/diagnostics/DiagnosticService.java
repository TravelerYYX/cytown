package com.yyx.ciyuantown.common.diagnostics;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Service
public class DiagnosticService {
    private final DependencyChecks probes;
    private final long timeoutMs;
    private final ExecutorService executor;
    private DiagnosticReport cached;
    private long expiresAt;

    @org.springframework.beans.factory.annotation.Autowired
    public DiagnosticService(DependencyChecks probes) { this(probes, 8000); }

    DiagnosticService(DependencyChecks probes, long timeoutMs) {
        this.probes = probes;
        this.timeoutMs = timeoutMs;
        // A stalled dependency cannot create unbounded background tasks on repeated public requests.
        this.executor = new ThreadPoolExecutor(7, 7, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            var thread = new Thread(runnable, "dependency-diagnostic");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized DiagnosticReport report() {
        if (cached != null && System.nanoTime() < expiresAt) return cached;
        long started = System.nanoTime();
        Instant checkedAt = Instant.now();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        var checks = probes.checks();
        List<Future<DiagnosticReport.Item>> futures = new ArrayList<>();
        for (var check : checks) {
            try { futures.add(executor.submit(() -> inspect(check))); }
            catch (RejectedExecutionException busy) {
                futures.add(CompletableFuture.completedFuture(new DiagnosticReport.Item(check.id(), check.name(), "WARN",
                        "上次检测尚未结束，暂时无法进行本项检测", "等待约 30 秒后重新检测；若持续出现，请检查依赖连接超时配置", 0)));
            }
        }
        List<DiagnosticReport.Item> results = new ArrayList<>();
        boolean interrupted = false;
        try {
            for (int index = 0; index < checks.size(); index++) {
                var future = futures.get(index);
                var check = checks.get(index);
                try {
                    results.add(future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (TimeoutException timeout) {
                    future.cancel(true);
                    results.add(new DiagnosticReport.Item(check.id(), check.name(), "DOWN",
                            "检测超过 " + timeoutMs / 1000.0 + " 秒仍未完成，连接或响应可能超时", check.suggestion(), elapsed(started)));
                } catch (InterruptedException stopped) {
                    interrupted = true;
                    future.cancel(true);
                    results.add(new DiagnosticReport.Item(check.id(), check.name(), "WARN", "检测被中断", "重新打开页面检测", elapsed(started)));
                } catch (ExecutionException error) {
                    results.add(new DiagnosticReport.Item(check.id(), check.name(), "DOWN",
                            DiagnosticFailures.describe(error.getCause()), check.suggestion(), elapsed(started)));
                }
            }
        } finally {
            for (var future : futures) if (!future.isDone()) future.cancel(true);
            if (interrupted) Thread.currentThread().interrupt();
        }
        // Keep failed checks first so a browser visit immediately shows what needs attention.
        results.sort(java.util.Comparator.comparingInt(item -> switch (item.status()) {
            case "DOWN" -> 0; case "WARN" -> 1; case "UP" -> 2; default -> 3;
        }));
        String status = results.stream().anyMatch(item -> "DOWN".equals(item.status())) ? "DOWN"
                : results.stream().anyMatch(item -> "WARN".equals(item.status())) ? "WARN" : "UP";
        cached = new DiagnosticReport(status, checkedAt, elapsed(started), List.copyOf(results));
        expiresAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        return cached;
    }

    private DiagnosticReport.Item inspect(DependencyChecks.Check check) {
        long started = System.nanoTime();
        try {
            var outcome = check.probe().run();
            return new DiagnosticReport.Item(check.id(), check.name(), outcome.status(), outcome.message(), outcome.suggestion(), elapsed(started));
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new DiagnosticReport.Item(check.id(), check.name(), "DOWN", DiagnosticFailures.describe(error), check.suggestion(), elapsed(started));
        }
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
