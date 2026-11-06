package com.yyx.ciyuantown.business.post.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.homeconfig.mapper.HomeChannelMapper;
import com.yyx.ciyuantown.business.interaction.service.InteractionService;
import com.yyx.ciyuantown.business.post.dto.PostQueryParam;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.business.post.vo.PostListVO;
import com.yyx.ciyuantown.business.publish.entity.Topic;
import com.yyx.ciyuantown.business.publish.mapper.TopicMapper;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;

@RestController
@RequestMapping("/app/search")
@RequiredArgsConstructor
public class AppSearchController {
    private final PostService posts;
    private final InteractionService interactions;
    private final AppUserMapper users;
    private final TopicMapper topics;
    private final HomeChannelMapper channels;

    @GetMapping("/posts")
    public Result<Page<PostListVO>> posts(@ModelAttribute PostQueryParam query,
        @RequestParam(defaultValue = "all") String type,
        @RequestParam(defaultValue = "hot") String sort, HttpServletRequest request) {
        normalizeChannel(query);
        var page = posts.search(query, type, "hot".equals(sort));
        return Result.success(page.setRecords(interactions.withState(page.getRecords(),
            (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID))));
    }

    @GetMapping("/users")
    public Result<Page<PublicUser>> users(@ModelAttribute PostQueryParam query) {
        query.validate();
        var page = new Page<PublicUser>(query.getPageNum(), query.getPageSize(), 0);
        if (query.getKeyword() == null || query.getKeyword().isBlank()) return Result.success(page);
        String keyword = query.getKeyword().trim();
        var filter = new LambdaQueryWrapper<AppUser>().eq(AppUser::getDeleted, false)
            .eq(AppUser::getStatus, 0)
            .and(w -> w.apply("nickname LIKE {0} ESCAPE '!'", contains(keyword))
                .or().apply("CAST(public_uid AS CHAR) = {0}", keyword));
        long total = users.selectCount(filter);
        page.setTotal(total);
        if (query.offset() >= total) return Result.success(page);
        filter.select(AppUser::getId, AppUser::getPublicUid, AppUser::getNickname,
                AppUser::getAvatar, AppUser::getSignature)
            .orderByDesc(AppUser::getId).last("LIMIT " + query.offset() + "," + query.getPageSize());
        return Result.success(page.setRecords(users.selectList(filter).stream().map(user ->
            new PublicUser(user.getId().toString(), user.getPublicUid() == null ? null : user.getPublicUid().toString(),
                user.getNickname(), user.getAvatar(), user.getSignature())).toList()));
    }

    @GetMapping("/suggestions")
    public Result<List<String>> suggestions(@ModelAttribute PostQueryParam query) {
        normalizeChannel(query);
        if (query.getKeyword() == null || query.getKeyword().isBlank()) return Result.success(List.of());
        String keyword = query.getKeyword().trim();
        var results = new LinkedHashSet<String>();
        topics.selectList(enabledTopics().apply("name LIKE {0} ESCAPE '!'", contains(keyword)).last("LIMIT 6"))
            .forEach(topic -> results.add(topic.getName()));
        if (results.size() < 6) results.addAll(posts.suggestTitles(query));
        return Result.success(results.stream().filter(value -> !value.equalsIgnoreCase(keyword))
            .filter(value -> !value.isBlank() && value.length() <= 100).limit(5).toList());
    }

    @GetMapping("/trending")
    public Result<List<String>> trending() {
        return Result.success(topics.selectList(enabledTopics().eq(Topic::getHot, true).last("LIMIT 3"))
            .stream().map(Topic::getName).toList());
    }

    private void normalizeChannel(PostQueryParam query) {
        query.validate();
        if (query.getChannelId() == null) return;
        var channel = channels.selectById(query.getChannelId());
        if (channel != null && Boolean.TRUE.equals(channel.getShowAllPosts())) query.setChannelId(null);
    }

    private LambdaQueryWrapper<Topic> enabledTopics() {
        return new LambdaQueryWrapper<Topic>().eq(Topic::getEnabled, true).eq(Topic::getStatus, 1)
            .orderByAsc(Topic::getSortOrder, Topic::getId);
    }

    private static String contains(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public record PublicUser(String id, String publicUid, String nickname, String avatar, String signature) {}
}
