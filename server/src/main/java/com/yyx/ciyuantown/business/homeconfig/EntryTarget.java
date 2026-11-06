package com.yyx.ciyuantown.business.homeconfig;

import com.yyx.ciyuantown.common.exception.BizException;
import java.net.URI;
import java.util.*;

/** Shared destination catalog and validation for both configurable entry groups. */
public final class EntryTarget {
    private EntryTarget() {}
    public record Page(String label, String value, String hint) {}
    public static final List<Page> PAGES = List.of(
        new Page("首页", "/pages/index/index?tab=home", ""),
        new Page("关注", "/pages/index/index?tab=follow", ""),
        new Page("消息", "/pages/index/index?tab=message", ""),
        new Page("我的", "/pages/index/index?tab=mine", ""),
        new Page("设置", "/pages/settings/index", ""),
        new Page("我的等级", "/pages/level/index", ""),
        new Page("每日签到", "/pages/checkin/index", ""),
        new Page("浏览历史", "/pages/browse-history/index", ""),
        new Page("次元币", "/pages/coin/index", ""),
        new Page("商铺", "/pages/shop/index", ""),
        new Page("会员商品", "/pages/membership/index", ""),
        new Page("我的订阅", "/pages/membership/subscription", ""),
        new Page("编辑资料", "/pages/profile-edit/index", ""),
        new Page("修改头像", "/pages/profile-edit/avatar", ""),
        new Page("修改昵称", "/pages/profile-edit/nickname", ""),
        new Page("个性签名", "/pages/profile-edit/signature", ""),
        new Page("个性标签", "/pages/profile-edit/tags", ""),
        new Page("卡片背景", "/pages/profile-edit/card-background", ""),
        new Page("头像框", "/pages/settings/avatar-frame", ""),
        new Page("账号与安全", "/pages/settings/account-security/index", ""),
        new Page("切换账号", "/pages/switch-account/index", ""),
        new Page("登录", "/pages/login/index", ""),
        new Page("发布", "/pages/publish/index", ""),
        new Page("粉丝", "/pages/relationships/index?type=followers", ""),
        new Page("关注的人", "/pages/relationships/index?type=following", ""),
        new Page("帖子详情", "/pages/post/index?id=", "请在页面地址的 id= 后填写帖子 ID"),
        new Page("通知列表", "/pages/notice/list?categoryId=", "请在 categoryId= 后填写通知分类 ID，可追加 &name=分类名称"),
        new Page("通知详情", "/pages/notice/detail?id=", "请在页面地址的 id= 后填写通知 ID")
    );

    public static String type(String type) {
        if (type == null) return "link"; // Requests from existing admin clients.
        if (!Set.of("link", "app").contains(type)) throw new BizException("跳转类型不合法");
        return type;
    }

    public static String validate(String type, String value) {
        String target = value == null ? "" : value.strip();
        if ("app".equals(type(type))) return validatePage(target);
        try {
            URI uri = new URI(target);
            if (target.length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null ||
                    !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                throw new IllegalArgumentException();
        } catch (Exception e) { throw new BizException("跳转链接必须是有效的 HTTP/HTTPS 完整链接"); }
        return target;
    }

    private static String validatePage(String target) {
        try {
            URI uri = new URI(target);
            String path = uri.getRawPath();
            if (target.length() > 2048 || uri.getScheme() != null || uri.getRawAuthority() != null || uri.getRawFragment() != null ||
                    PAGES.stream().noneMatch(page -> page.value().split("\\?", 2)[0].equals(path)))
                throw new IllegalArgumentException();
            Map<String, String> query = new HashMap<>();
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                // Route keys/IDs stay literal, and duplicate keys must not override validation.
                if (!pair[0].matches("[A-Za-z][A-Za-z0-9]*") || query.putIfAbsent(pair[0], pair.length == 2 ? pair[1] : "") != null)
                    throw new IllegalArgumentException();
            }
            if (Set.of("/pages/post/index", "/pages/notice/detail").contains(path)) positiveId(query.get("id"));
            if ("/pages/notice/list".equals(path)) positiveId(query.get("categoryId"));
            if ("/pages/index/index".equals(path) && !Set.of("home", "follow", "message", "mine").contains(query.getOrDefault("tab", "home")))
                throw new IllegalArgumentException();
            if ("/pages/relationships/index".equals(path) && !Set.of("followers", "following").contains(query.getOrDefault("type", "followers")))
                throw new IllegalArgumentException();
        } catch (Exception e) { throw new BizException("请选择有效的 APP 页面，帖子/通知需填写有效的 ID 参数"); }
        return target;
    }

    private static void positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}") || Long.parseLong(value) <= 0) throw new IllegalArgumentException();
    }
}
