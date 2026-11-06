package com.yyx.ciyuantown.business.appuser.mapper;
import com.yyx.ciyuantown.business.appuser.vo.UserStatisticsVO;
import org.apache.ibatis.annotations.*;
@Mapper
public interface UserStatisticsMapper {
    @Select("""
        SELECT
          (SELECT COUNT(*) FROM post WHERE author_id=#{id} AND status=1) AS postCount,
          (SELECT COUNT(*) FROM user_follow WHERE followee_id=#{id}) AS followerCount,
          (SELECT COUNT(*) FROM user_follow WHERE follower_id=#{id}) AS followingCount,
          (SELECT COUNT(*) FROM user_like l JOIN post p ON p.id=l.post_id WHERE p.author_id=#{id} AND p.status=1) AS receivedLikeCount,
          (SELECT COUNT(*) FROM user_favorite f JOIN post p ON p.id=f.post_id WHERE f.user_id=#{id} AND p.status=1) AS favoriteCount,
          COALESCE((SELECT active_days FROM app_user WHERE id=#{id}),0) AS activeDays
        """)
    UserStatisticsVO count(@Param("id") Long id);
}
