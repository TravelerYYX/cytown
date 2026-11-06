package com.yyx.ciyuantown.business.mineentry;

import com.yyx.ciyuantown.business.homeconfig.EntryTarget;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MembershipTarget {
    private static final String KEY = "mine_membership_target";
    private static final String DEFAULT = "/pages/membership/index";
    private final JdbcTemplate jdbc;

    public record Config(String target) {}

    public Config get() {
        var rows = jdbc.queryForList("SELECT config_value FROM system_config WHERE config_key=?", String.class, KEY);
        return new Config(rows.isEmpty() ? DEFAULT : rows.getFirst());
    }

    public Config save(Config config) {
        String target = EntryTarget.validate("app", config == null ? null : config.target());
        if (target.length() > 500) throw new BizException("会员入口页面地址最多 500 个字符");
        jdbc.update("""
                INSERT INTO system_config (config_key, config_value, remark) VALUES (?, ?, '我的会员入口跳转页面')
                ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)
                """, KEY, target);
        return new Config(target);
    }
}
