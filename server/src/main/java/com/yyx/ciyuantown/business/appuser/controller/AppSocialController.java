package com.yyx.ciyuantown.business.appuser.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.business.appuser.mapper.UserStatisticsMapper;
import com.yyx.ciyuantown.business.appuser.vo.*;
import com.yyx.ciyuantown.business.post.dto.PostQueryParam;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/app/user") @AppLoginRequired @RequiredArgsConstructor
public class AppSocialController {
    private final JdbcTemplate jdbc;
    private final UserStatisticsMapper statistics;
    private Long viewer(HttpServletRequest request) { return (Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID); }

    @GetMapping("/statistics")
    public Result<UserStatisticsVO> statistics(HttpServletRequest request) { return Result.success(statistics.count(viewer(request))); }

    @GetMapping("/followers") @Transactional(readOnly=true)
    public Result<Page<UserRelationshipVO>> followers(@ModelAttribute PostQueryParam query, HttpServletRequest request) {
        return Result.success(relationships(query, viewer(request), true));
    }
    @GetMapping("/following") @Transactional(readOnly=true)
    public Result<Page<UserRelationshipVO>> following(@ModelAttribute PostQueryParam query, HttpServletRequest request) {
        return Result.success(relationships(query, viewer(request), false));
    }
    private Page<UserRelationshipVO> relationships(PostQueryParam query, Long userId, boolean followers) {
        query.validate();
        // Column names come exclusively from this boolean, never request input.
        String owner = followers ? "followee_id" : "follower_id";
        String other = followers ? "follower_id" : "followee_id";
        String from = " FROM user_follow f JOIN app_user u ON u.id=f."+other+" WHERE f."+owner+"=?";
        long total = jdbc.queryForObject("SELECT COUNT(*)"+from, Long.class, userId);
        var page = new Page<UserRelationshipVO>(query.getPageNum(), query.getPageSize(), total);
        var records = jdbc.query("SELECT u.id,u.public_uid,u.deleted,u.nickname,u.avatar,u.avatar_frame,u.avatar_frame_expires_at,u.level,u.signature,"+
            "EXISTS(SELECT 1 FROM user_follow a WHERE a.follower_id=? AND a.followee_id=u.id) AS following,"+
            "EXISTS(SELECT 1 FROM user_follow b WHERE b.follower_id=u.id AND b.followee_id=?) AS followed_by"+
            from+" ORDER BY f.create_time DESC,f.id DESC LIMIT ?,?", (rs,n) -> new UserRelationshipVO(
                rs.getString("id"),rs.getString("public_uid"),rs.getBoolean("deleted") ? "已注销用户" : rs.getString("nickname"),
                rs.getBoolean("deleted") ? "" : rs.getString("avatar"),rs.getBoolean("deleted") ? 0 : rs.getInt("level"),
                rs.getBoolean("deleted") ? "" : rs.getString("signature"),rs.getBoolean("following"),rs.getBoolean("followed_by"),rs.getBoolean("deleted") ? "" : rs.getString("avatar_frame"),rs.getObject("avatar_frame_expires_at",Long.class)),
            userId,userId,userId,query.offset(),query.getPageSize());
        return page.setRecords(records);
    }
}
