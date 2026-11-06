package com.yyx.ciyuantown.common.storage;

import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class GlobalSettingsService {
    static final String KEY = "delete_resource_files_enabled";
    private final JdbcTemplate db;
    public record Settings(Boolean deleteResourceFilesEnabled) {}
    public boolean enabled() {
        var values = db.queryForList("SELECT config_value FROM system_config WHERE config_key=?", String.class, KEY);
        return values.isEmpty() || "1".equals(values.getFirst()) || "true".equalsIgnoreCase(values.getFirst());
    }
    public Settings get() { return new Settings(enabled()); }
    @Transactional public Settings save(Settings value) {
        if(value == null || value.deleteResourceFilesEnabled() == null) throw new BizException("资源删除开关不能为空");
        db.queryForList("SELECT id FROM media_cleanup_lock WHERE id=1 FOR UPDATE");
        db.update("INSERT INTO system_config(config_key,config_value,remark) VALUES(?,?,?) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",
            KEY, value.deleteResourceFilesEnabled() ? "1" : "0", "删除时同时删除资源文件");
        return get();
    }
}
