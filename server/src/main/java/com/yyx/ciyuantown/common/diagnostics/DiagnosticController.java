package com.yyx.ciyuantown.common.diagnostics;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@RestController
public class DiagnosticController {
    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final DiagnosticService diagnostics;
    private final String template;

    public DiagnosticController(DiagnosticService diagnostics) throws IOException {
        this.diagnostics = diagnostics;
        this.template = new ClassPathResource("diagnostics.html").getContentAsString(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/diagnostics", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> page() {
        var report = diagnostics.report();
        StringBuilder rows = new StringBuilder();
        for (var item : report.checks()) {
            rows.append("<tr><td class=\"component\">").append(escape(item.name()))
                    .append("</td><td><span class=\"badge ").append(cssStatus(item.status())).append("\">")
                    .append(label(item.status())).append("</span></td><td class=\"message\">")
                    .append(escape(item.message())).append("</td><td class=\"suggestion\">")
                    .append(escape(item.suggestion())).append("</td><td class=\"duration\">")
                    .append(item.durationMs()).append(" ms</td></tr>");
        }
        long failures = report.checks().stream().filter(item -> "DOWN".equals(item.status())).count();
        long warnings = report.checks().stream().filter(item -> "WARN".equals(item.status())).count();
        String summary = failures > 0 ? failures + " 项异常，需要处理" : warnings > 0 ? warnings + " 项需要确认" : "已检测项目运行正常";
        String body = template.replace("{{statusClass}}", cssStatus(report.status()))
                .replace("{{summary}}", summary).replace("{{checkedAt}}", TIME.format(report.checkedAt()))
                .replace("{{duration}}", String.valueOf(report.durationMs())).replace("{{rows}}", rows);
        return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
                .body(body);
    }

    @GetMapping(value = "/diagnostics.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DiagnosticReport> json() {
        var report = diagnostics.report();
        return ResponseEntity.status("DOWN".equals(report.status()) ? 503 : 200)
                .cacheControl(CacheControl.noStore()).body(report);
    }

    private static String escape(String value) { return HtmlUtils.htmlEscape(value, "UTF-8"); }
    private static String cssStatus(String status) {
        return switch (status) { case "UP" -> "up"; case "DOWN" -> "down"; case "WARN" -> "warn"; default -> "skipped"; };
    }
    private static String label(String status) {
        return switch (status) { case "UP" -> "正常"; case "DOWN" -> "异常"; case "WARN" -> "待确认"; default -> "已跳过"; };
    }
}
