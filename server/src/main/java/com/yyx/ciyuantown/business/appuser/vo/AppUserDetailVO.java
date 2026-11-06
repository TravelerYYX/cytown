package com.yyx.ciyuantown.business.appuser.vo;
import java.util.List;
public record AppUserDetailVO(String id, String publicUid, String account, String qqNumber, String email,
    String nickname, String avatar, String signature, Integer gender, Integer level, Long experience, String cardBackground,
    List<UserTagVO> tags, Integer status, boolean hasPassword, String officialAdminId,
    String createTime, String updateTime, String lastLoginTime, UserStatisticsVO statistics) {}
