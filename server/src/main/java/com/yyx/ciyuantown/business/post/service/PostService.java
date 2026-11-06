package com.yyx.ciyuantown.business.post.service;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.post.dto.PostQueryParam;
import com.yyx.ciyuantown.business.post.vo.*;
import com.yyx.ciyuantown.business.post.entity.Post;
public interface PostService {
    Page<PostListVO> search(PostQueryParam query, String type, boolean hot);
    java.util.List<String> suggestTitles(PostQueryParam query);
    Page<PostListVO> list(PostQueryParam query, boolean publishedOnly);
    Page<PostListVO> listPublicByAuthor(PostQueryParam query, Long authorId);
    Page<PostListVO> listMine(PostQueryParam query, Long authorId);
    Page<PostListVO> searchMine(PostQueryParam query, Long userId, String scope);
    Page<PostListVO> listFollowing(PostQueryParam query, Long userId);
    Page<PostListVO> listFavorites(PostQueryParam query, Long userId);
    Page<PostListVO> listLikes(PostQueryParam query, Long userId);
    Page<PostListVO> listHistory(PostQueryParam query, Long userId);
    void recordView(Long postId, Long userId);
    PostDetailVO detail(Long id, boolean publishedOnly);
    Post requirePost(Long id, boolean publishedOnly);
    void updateStatus(Long id, Integer status);
    void updateHomeNews(Long id, Boolean homeNewsEnabled);
    void delete(Long id);
    void setProfilePinned(Long id, Long authorId, boolean pinned);
    void editOwnedText(Long id, Long authorId, String title, String content);
    void deleteOwned(Long id, Long authorId);
}
