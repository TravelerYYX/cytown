package com.yyx.ciyuantown.business.sys.vo;
import com.yyx.ciyuantown.business.appuser.vo.*;
public record OfficialAccountDetailVO(String id, String username, String nickname, String avatar,
    Integer userType, Integer status, boolean hasPassword, String createdBy, String createdByName,
    String createTime, String updateTime, AppUserDetailVO community, UserStatisticsVO statistics) {}
