package com.yyx.ciyuantown.business.interaction.service;
import com.yyx.ciyuantown.business.interaction.vo.InteractionResultVO;
import com.yyx.ciyuantown.business.post.vo.PostDetailVO;
import com.yyx.ciyuantown.business.post.vo.PostListVO;
import java.util.List;
public interface InteractionService {
    InteractionResultVO toggleLike(Long userId, Long postId);
    InteractionResultVO toggleFavorite(Long userId, Long postId);
    InteractionResultVO toggleFollow(Long userId,Long followeeId);
    InteractionResultVO followState(Long userId,Long followeeId);
    void fillState(PostDetailVO post, Long userId);
    List<PostListVO> withState(List<PostListVO> posts, Long userId);
}
