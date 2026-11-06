package com.yyx.ciyuantown.business.post.service;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.post.dto.CommentCreateParam;
import com.yyx.ciyuantown.business.post.dto.CommentQueryParam;
import com.yyx.ciyuantown.business.post.vo.CommentVO;
import com.yyx.ciyuantown.business.post.vo.MineCommentVO;
import com.yyx.ciyuantown.business.publish.entity.EmojiGroup;
import com.yyx.ciyuantown.business.publish.entity.PublishEmoji;
import java.util.List;
public interface CommentService {
    record Config(int imageMax,int contentMax,List<EmojiGroup> emojiGroups,List<PublishEmoji> emojis) {}
    record ReactionResult(String reaction,int likeCount) {}
    record DeleteResult(int deletedCount,boolean tombstone) {}
    Page<CommentVO> list(Long postId, CommentQueryParam query, Long userId);
    Page<MineCommentVO> mine(Long userId, CommentQueryParam query);
    Page<CommentVO> replies(Long postId,Long rootId,CommentQueryParam query,Long userId);
    Page<CommentVO> listPreview(Long postId,CommentQueryParam query);
    Page<CommentVO> repliesPreview(Long postId,Long rootId,CommentQueryParam query);
    record Focus(String commentId,String rootId,int rootPage,int replyPage,Page<CommentVO> roots,Page<CommentVO> replies) {}
    Focus locate(Long postId,Long commentId,CommentQueryParam query,Long userId);
    Config config();
    CommentVO create(Long postId,Long userId,CommentCreateParam param);
    ReactionResult react(Long postId,Long commentId,Long userId,String reaction);
    DeleteResult delete(Long postId,Long commentId,Long userId);
}
