package com.yyx.ciyuantown.business.appuser.service;

import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class UserActivityService {
    private final JdbcTemplate jdbc;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public void record(AppUser user) {
        LocalDate today = LocalDate.now(ZONE);
        if (user.getLastActiveDate() != null && !user.getLastActiveDate().isBefore(today)) return;
        // The conditional update also deduplicates simultaneous logins/requests from multiple devices.
        jdbc.update("""
            UPDATE app_user SET active_days=active_days+1,last_active_date=?
            WHERE id=? AND deleted=0 AND status=0
              AND (last_active_date IS NULL OR last_active_date<?)
            """, today, user.getId(), today);
    }
}
