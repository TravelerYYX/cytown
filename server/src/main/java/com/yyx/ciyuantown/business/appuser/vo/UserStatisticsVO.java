package com.yyx.ciyuantown.business.appuser.vo;
public record UserStatisticsVO(long postCount, long followerCount, long followingCount, long receivedLikeCount, long favoriteCount, long activeDays) {
    public static UserStatisticsVO empty() { return new UserStatisticsVO(0,0,0,0,0,0); }
}
