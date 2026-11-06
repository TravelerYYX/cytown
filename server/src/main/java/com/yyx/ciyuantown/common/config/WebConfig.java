package com.yyx.ciyuantown.common.config;

import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import com.yyx.ciyuantown.common.interceptor.AppAuthInterceptor;
import com.yyx.ciyuantown.common.interceptor.ReadOnlyInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Web 配置：拦截器注册 + 静态资源映射 + CORS
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    /** 上传文件根目录 */
    @Value("${ciyuantown.upload-dir:./uploads}")
    private String uploadDir;

    private final AuthInterceptor authInterceptor;
    private final AppAuthInterceptor appAuthInterceptor;
    private final ReadOnlyInterceptor readOnlyInterceptor;

    @Value("${ciyuantown.cors-origins}")
    private String[] corsOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(appAuthInterceptor).addPathPatterns("/app/**");
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                // /uploads/** 为静态图片资源，<img> 标签无法携带 Authorization 头，需免鉴权
                // P1 APP公开内容只读接口；后续新增写接口必须单独鉴权。
                .excludePathPatterns("/auth/login", "/auth/refreshToken", "/uploads/**", "/app/**", "/error", "/diagnostics", "/diagnostics.json");
        // 只读账号拦截：必须在 authInterceptor 之后注册（先鉴权再判权限），排除路径与其对齐
        registry.addInterceptor(readOnlyInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/login", "/auth/refreshToken", "/uploads/**", "/app/**", "/error", "/diagnostics", "/diagnostics.json");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 上传文件静态访问：/uploads/** -> {upload-dir}/
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toString().replace("\\", "/");
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + location + "/", "classpath:/static/uploads/");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
