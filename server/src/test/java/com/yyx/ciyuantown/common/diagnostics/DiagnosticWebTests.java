package com.yyx.ciyuantown.common.diagnostics;

import com.yyx.ciyuantown.common.config.WebConfig;
import com.yyx.ciyuantown.common.interceptor.AppAuthInterceptor;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import com.yyx.ciyuantown.common.interceptor.ReadOnlyInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DiagnosticWebTests {
    @Test void anonymousHtmlAndJsonBypassOnlyTheDiagnosticRoutesAndEscapeMessages() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.setEnvironment(new MockEnvironment().withProperty("ciyuantown.cors-origins", "http://localhost"));
            context.register(TestWebConfiguration.class);
            context.refresh();
            var service = context.getBean(DiagnosticService.class);
            var report = new DiagnosticReport("DOWN", Instant.parse("2026-10-13T01:00:00Z"), 5,
                    List.of(new DiagnosticReport.Item("mysql", "MySQL", "DOWN", "<script>bad</script>", "检查配置", 4)));
            when(service.report()).thenReturn(report);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/diagnostics")).andExpect(status().isOk())
                    .andExpect(content().contentType("text/html;charset=UTF-8"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("1 项异常")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;bad&lt;/script&gt;")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-10-13 09:00:00")))
                    .andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(get("/diagnostics.json")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.checks[0].id").value("mysql"));
            verifyNoInteractions(context.getBean(AuthInterceptor.class), context.getBean(ReadOnlyInterceptor.class));
            mvc.perform(get("/private-test")).andExpect(status().isUnauthorized());
            mvc.perform(get("/diagnostics/other")).andExpect(status().isUnauthorized());
            when(service.report()).thenReturn(new DiagnosticReport("UP", Instant.now(), 1, List.of()));
            mvc.perform(get("/diagnostics.json")).andExpect(status().isOk());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({WebConfig.class, DiagnosticController.class})
    static class TestWebConfiguration {
        @Bean DiagnosticService diagnostics() { return mock(DiagnosticService.class); }
        @Bean AuthInterceptor auth() throws Exception {
            var interceptor = mock(AuthInterceptor.class);
            when(interceptor.preHandle(any(), any(), any())).thenAnswer(call -> {
                ((jakarta.servlet.http.HttpServletResponse) call.getArgument(1)).setStatus(401);
                return false;
            });
            return interceptor;
        }
        @Bean AppAuthInterceptor appAuth() { return mock(AppAuthInterceptor.class); }
        @Bean ReadOnlyInterceptor readOnly() { return mock(ReadOnlyInterceptor.class); }
        @Bean PrivateController privateController() { return new PrivateController(); }
    }

    @RestController
    static class PrivateController {
        @GetMapping("/private-test") public String privateRoute() { return "private"; }
        @GetMapping("/diagnostics/other") public String other() { return "private"; }
    }
}
