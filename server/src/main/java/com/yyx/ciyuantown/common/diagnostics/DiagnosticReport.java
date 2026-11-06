package com.yyx.ciyuantown.common.diagnostics;

import java.time.Instant;
import java.util.List;

public record DiagnosticReport(String status, Instant checkedAt, long durationMs, List<Item> checks) {
    public record Item(String id, String name, String status, String message, String suggestion, long durationMs) {}
}
