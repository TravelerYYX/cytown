package com.yyx.ciyuantown.common.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class LegalLinksService {
    private static final String KEY = "settings_legal_links";
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public record Link(String linkUrl, String openMode) {}
    public record Settings(Link userAgreement, Link privacyPolicy, Link personalInfoCollection) {}

    public static Settings defaults() {
        var empty = new Link("", "browser");
        return new Settings(empty, empty, empty);
    }

    public Settings get() {
        var rows = db.queryForList("SELECT config_value FROM system_config WHERE config_key=?", String.class, KEY);
        if (rows.isEmpty()) return defaults();
        try {
            return validate(json.readValue(rows.getFirst(), Settings.class));
        } catch (JsonProcessingException e) {
            throw new BizException("协议链接配置无法读取，请重新保存配置");
        }
    }

    public Settings save(Settings value) {
        var settings = validate(value);
        try {
            db.update("INSERT INTO system_config(config_key,config_value,remark) VALUES(?,?,?) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",
                KEY, json.writeValueAsString(settings), "设置页协议链接及打开方式");
        } catch (JsonProcessingException e) {
            throw new BizException("协议链接配置保存失败");
        }
        return settings;
    }

    private Settings validate(Settings value) {
        if (value == null) throw new BizException("协议链接配置不能为空");
        return new Settings(validateLink(value.userAgreement(), "用户协议"),
            validateLink(value.privacyPolicy(), "隐私政策"),
            validateLink(value.personalInfoCollection(), "个人信息收集清单"));
    }

    private Link validateLink(Link value, String title) {
        if (value == null) throw new BizException(title + "配置不能为空");
        if (value.openMode() == null || !Set.of("browser", "inapp").contains(value.openMode()))
            throw new BizException(title + "打开方式不合法");
        String url = value.linkUrl() == null ? "" : value.linkUrl().trim();
        if (!url.isEmpty()) {
            try {
                URI uri = URI.create(url);
                if (url.length() > 2048 || uri.getScheme() == null ||
                    !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) ||
                    uri.getHost() == null || uri.getUserInfo() != null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new BizException(title + "链接需为有效的 HTTP 或 HTTPS 完整地址（最多2048字符）");
            }
        }
        return new Link(url, value.openMode());
    }
}
