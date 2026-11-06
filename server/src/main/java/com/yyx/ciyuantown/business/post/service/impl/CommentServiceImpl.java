package com.yyx.ciyuantown.business.post.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.homeconfig.entity.SystemConfig;
import com.yyx.ciyuantown.business.homeconfig.mapper.SystemConfigMapper;
import com.yyx.ciyuantown.business.post.dto.CommentCreateParam;
import com.yyx.ciyuantown.business.post.dto.CommentQueryParam;
import com.yyx.ciyuantown.business.post.entity.*;
import com.yyx.ciyuantown.business.post.mapper.*;
import com.yyx.ciyuantown.business.post.service.CommentService;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.business.post.vo.CommentVO;
import com.yyx.ciyuantown.business.post.vo.MineCommentVO;
import com.yyx.ciyuantown.business.publish.entity.*;
import com.yyx.ciyuantown.business.publish.mapper.*;
import com.yyx.ciyuantown.business.publish.service.PublishMediaService;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {
    private final com.yyx.ciyuantown.business.interaction.service.SocialInboxService inbox;
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
    private static final int CONTENT_MAX = 500;
    private final CommentMapper comments;
    private final CommentMediaMapper commentMedia;
    private final CommentReactionMapper reactions;
    private final PostMapper postMapper;
    private final PostService posts;
    private final AppUserMapper users;
    private final com.yyx.ciyuantown.business.appuser.service.AppUserAccessService userAccess;
    private final UploadAssetMapper assets;
    private final EmojiGroupMapper groups;
    private final PublishEmojiMapper emojis;
    private final SystemConfigMapper configs;
    private final RedisHotspotService redis;
    private final com.yyx.ciyuantown.business.level.LevelService levels;

    @Override
    @Transactional(readOnly = true)
    public Config config() {
        List<EmojiGroup> visibleGroups = groups.selectList(new LambdaQueryWrapper<EmojiGroup>()
            .eq(EmojiGroup::getEnabled, true).orderByAsc(EmojiGroup::getSortOrder, EmojiGroup::getId));
        Set<Long> groupIds = visibleGroups.stream().map(EmojiGroup::getId).collect(Collectors.toSet());
        List<PublishEmoji> visibleEmojis = groupIds.isEmpty() ? List.of() : emojis.selectList(new LambdaQueryWrapper<PublishEmoji>()
            .eq(PublishEmoji::getEnabled, true).in(PublishEmoji::getGroupId, groupIds)
            .orderByAsc(PublishEmoji::getSortOrder, PublishEmoji::getId));
        return new Config(imageMax(), CONTENT_MAX, visibleGroups, visibleEmojis);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CommentVO> list(Long postId, CommentQueryParam q, Long userId) {
        return listInternal(postId,q,userId,true);
    }
    @Override @Transactional(readOnly=true)
    public Page<MineCommentVO> mine(Long userId, CommentQueryParam q) {
        if (userId == null) throw new BizException("请先登录");
        q.validate();
        var filter = new LambdaQueryWrapper<Comment>().eq(Comment::getAuthorId,userId).eq(Comment::getDeleted,false);
        long total = comments.selectCount(filter);
        var page = new Page<MineCommentVO>(q.getPageNum(),q.getPageSize(),total);
        if (q.offset() >= total) return page.setRecords(List.of());
        var rows = comments.selectList(filter.orderByDesc(Comment::getCreateTime,Comment::getId)
            .last("LIMIT " + q.offset() + "," + q.getPageSize()));
        var postIds = rows.stream().map(Comment::getPostId).distinct().toList();
        var sourcePosts = postMapper.selectBatchIds(postIds).stream().collect(Collectors.toMap(Post::getId,p->p));
        var ids = rows.stream().map(Comment::getId).toList();
        var targetIds = rows.stream().map(Comment::getReplyToId).filter(Objects::nonNull).distinct().toList();
        Map<Long,Comment> targets = targetIds.isEmpty() ? Map.of() : comments.selectBatchIds(targetIds).stream().collect(Collectors.toMap(Comment::getId,c->c));
        var userIds = new HashSet<Long>(); userIds.add(userId);
        targets.values().stream().map(Comment::getAuthorId).filter(Objects::nonNull).forEach(userIds::add);
        var authors = users.selectBatchIds(userIds).stream().collect(Collectors.toMap(AppUser::getId,u->u));
        var media = commentMedia.selectList(new LambdaQueryWrapper<CommentMedia>().in(CommentMedia::getCommentId,ids)
            .orderByAsc(CommentMedia::getSortOrder,CommentMedia::getId)).stream().collect(Collectors.groupingBy(CommentMedia::getCommentId));
        var liked = reactions.selectList(new LambdaQueryWrapper<CommentReaction>().eq(CommentReaction::getUserId,userId)
            .eq(CommentReaction::getReaction,"like").in(CommentReaction::getCommentId,ids)).stream()
            .collect(Collectors.toMap(CommentReaction::getCommentId,r->r));
        var data = new ViewData(authors,targets,media,liked);
        return page.setRecords(rows.stream().map(c -> {
            Post source = sourcePosts.get(c.getPostId());
            var vo = new MineCommentVO();
            org.springframework.beans.BeanUtils.copyProperties(view(c,source,userId,false,data),vo);
            boolean available = source != null && Integer.valueOf(1).equals(source.getStatus());
            vo.setPostAvailable(available);
            vo.setPostMode(source == null ? "post" : orEmpty(source.getMode()));
            vo.setPostTitle(!available ? "帖子已下架" : !trim(source.getTitle()).isEmpty() ? source.getTitle()
                : !trim(source.getContent()).isEmpty() ? limit(source.getContent(),80) : "无标题帖子");
            return vo;
        }).toList());
    }
    @Override @Transactional(readOnly=true)
    public Page<CommentVO> listPreview(Long postId,CommentQueryParam q){return listInternal(postId,q,null,false);}
    private Page<CommentVO> listInternal(Long postId,CommentQueryParam q,Long userId,boolean publishedOnly) {
        q.validate();
        Post post = posts.requirePost(postId, publishedOnly);
        LambdaQueryWrapper<Comment> countFilter = rootFilter(post, q.getScope());
        long total = comments.selectCount(countFilter);
        Page<CommentVO> page = new Page<>(q.getPageNum(), q.getPageSize(), total);
        if (q.offset() >= total) return page.setRecords(List.of());
        LambdaQueryWrapper<Comment> rowsFilter = rootFilter(post, q.getScope());
        String order = "hot".equals(q.getSort())
            ? " ORDER BY pinned DESC,like_count DESC,create_time DESC,id DESC"
            : " ORDER BY pinned DESC,create_time DESC,id DESC";
        rowsFilter.last(order + " LIMIT " + q.offset() + "," + q.getPageSize());
        boolean authorOnly = "author".equals(q.getScope());
        return page.setRecords(comments.selectList(rowsFilter).stream().map(root -> {
            boolean contextOnly = authorOnly && !Objects.equals(root.getAuthorId(), post.getAuthorId());
            CommentVO vo = view(root, post, userId, contextOnly);
            LambdaQueryWrapper<Comment> replyFilter = activeReplies(postId, root.getId());
            if (authorOnly) replyFilter.eq(Comment::getAuthorId, post.getAuthorId());
            long replyCount = comments.selectCount(replyFilter);
            vo.setReplyCount(Math.toIntExact(replyCount));
            if (replyCount > 0) {
                LambdaQueryWrapper<Comment> preview = activeReplies(postId, root.getId());
                if (authorOnly) preview.eq(Comment::getAuthorId, post.getAuthorId());
                preview.orderByAsc(Comment::getCreateTime, Comment::getId).last("LIMIT 1");
                vo.setReplies(comments.selectList(preview).stream().map(c -> view(c, post, userId, false)).toList());
            }
            return vo;
        }).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CommentVO> replies(Long postId, Long rootId, CommentQueryParam q, Long userId) {
        return repliesInternal(postId,rootId,q,userId,true);
    }
    @Override @Transactional(readOnly=true)
    public Page<CommentVO> repliesPreview(Long postId,Long rootId,CommentQueryParam q){return repliesInternal(postId,rootId,q,null,false);}
    private Page<CommentVO> repliesInternal(Long postId,Long rootId,CommentQueryParam q,Long userId,boolean publishedOnly) {
        q.validate();
        Post post = posts.requirePost(postId, publishedOnly);
        Comment root = comments.selectById(rootId);
        if (root == null || !Objects.equals(root.getPostId(), postId) || root.getParentId() != null)
            throw new BizException("根评论不存在");
        LambdaQueryWrapper<Comment> filter = activeReplies(postId, rootId);
        if ("author".equals(q.getScope())) filter.eq(Comment::getAuthorId, post.getAuthorId());
        long total = comments.selectCount(filter);
        Page<CommentVO> page = new Page<>(q.getPageNum(), q.getPageSize(), total);
        if (q.offset() >= total) return page.setRecords(List.of());
        LambdaQueryWrapper<Comment> rows = activeReplies(postId, rootId);
        if ("author".equals(q.getScope())) rows.eq(Comment::getAuthorId, post.getAuthorId());
        rows.orderByAsc(Comment::getCreateTime, Comment::getId).last("LIMIT " + q.offset() + "," + q.getPageSize());
        return page.setRecords(comments.selectList(rows).stream().map(c -> view(c, post, userId, false)).toList());
    }

    @Override @Transactional(readOnly=true)
    public Focus locate(Long postId,Long commentId,CommentQueryParam q,Long userId) {
        q.validate();
        Post post=posts.requirePost(postId,true);
        Comment target=comments.selectById(commentId);
        if(target==null||!postId.equals(target.getPostId())||Boolean.TRUE.equals(target.getDeleted()))throw new BizException("评论不存在或已删除");
        if("author".equals(q.getScope())&&!Objects.equals(target.getAuthorId(),post.getAuthorId()))throw new BizException("该评论不在当前筛选范围内");
        Comment root=target.getParentId()==null?target:comments.selectById(target.getParentId());
        if(root==null)throw new BizException("评论不存在或已删除");
        var preceding=rootFilter(post,q.getScope());
        if("hot".equals(q.getSort()))preceding.apply("(pinned,like_count,create_time,id)>({0},{1},{2},{3})",Boolean.TRUE.equals(root.getPinned())?1:0,safe(root.getLikeCount()),root.getCreateTime(),root.getId());
        else preceding.apply("(pinned,create_time,id)>({0},{1},{2})",Boolean.TRUE.equals(root.getPinned())?1:0,root.getCreateTime(),root.getId());
        int rootPage=Math.toIntExact(comments.selectCount(preceding)/q.getPageSize()+1),replyPage=0;
        q.setPageNum(rootPage);
        Page<CommentVO> roots=listInternal(postId,q,userId,true),replyRows=null;
        if(target.getParentId()!=null){
            var before=activeReplies(postId,root.getId()).apply("(create_time,id)<({0},{1})",target.getCreateTime(),target.getId());
            if("author".equals(q.getScope()))before.eq(Comment::getAuthorId,post.getAuthorId());
            replyPage=Math.toIntExact(comments.selectCount(before)/q.getPageSize()+1);
            q.setPageNum(replyPage);replyRows=repliesInternal(postId,root.getId(),q,userId,true);
        }
        return new Focus(target.getId().toString(),root.getId().toString(),rootPage,replyPage,roots,replyRows);
    }

    @Override
    @Transactional
    public CommentVO create(Long postId, Long userId, CommentCreateParam p) {
        cleanup.lock();
        AppUser author = userAccess.requireForUpdate(userId);
        if (!redis.allow("comment", String.valueOf(userId), 30, Duration.ofMinutes(1)))
            throw new BizException("评论过于频繁，请稍后再试");
        Post post = posts.requirePost(postId, true);
        String content = trim(p == null ? null : p.content());
        List<Long> assetIds = distinct(p == null ? null : p.assetIds());
        Long emojiId = p == null ? null : p.emojiId();
        if (content.codePointCount(0, content.length()) > CONTENT_MAX) throw new BizException("评论不能超过500个字符");
        if (emojiId != null && (!content.isEmpty() || !assetIds.isEmpty())) throw new BizException("图片表情需要单独发送");
        if (emojiId == null && content.isEmpty() && assetIds.isEmpty()) throw new BizException("请输入评论内容");
        int max = imageMax();
        if (assetIds.size() > max) throw new BizException(max == 0 ? "评论图片已关闭" : "每条评论最多上传" + max + "张图片");

        Comment target = null;
        Comment root = null;
        Long targetId = p == null ? null : (p.replyToId() != null ? p.replyToId() : p.parentId());
        if (targetId != null) {
            target = comments.selectById(targetId);
            if (target == null || !Objects.equals(target.getPostId(), postId) || Boolean.TRUE.equals(target.getDeleted()))
                throw new BizException("回复的评论不存在或已删除");
            root = target.getParentId() == null ? target : comments.selectById(target.getParentId());
            if (root == null || Boolean.TRUE.equals(root.getDeleted())) throw new BizException("该会话已删除，不能继续回复");
            if (p.parentId() != null && !Objects.equals(p.parentId(), root.getId())) throw new BizException("回复会话不一致");
        }

        List<UploadAsset> mediaRows = resolveAssets(userId, assetIds);
        PublishEmoji sticker = null;
        if (emojiId != null) {
            sticker = emojis.selectById(emojiId);
            EmojiGroup group = sticker == null ? null : groups.selectById(sticker.getGroupId());
            if (sticker == null || !Boolean.TRUE.equals(sticker.getEnabled()) || group == null || !Boolean.TRUE.equals(group.getEnabled()))
                throw new BizException("表情不存在或已停用");
        }

        Comment row = new Comment();
        row.setPostId(postId);
        row.setParentId(root == null ? null : root.getId());
        row.setReplyToId(target == null ? null : target.getId());
        row.setReplyToNickname(target == null ? "" : target.getAuthorNickname());
        row.setAuthorId(userId);
        row.setAuthorNickname(author.getNickname());
        row.setAuthorLevel(author.getLevel());
        row.setRegion(limit(trim(p == null ? null : p.region()), 50));
        row.setContent(content);
        row.setContentType(sticker != null ? "sticker" : content.isEmpty() ? "image" : "text");
        row.setStickerId(sticker == null ? null : sticker.getId());
        row.setStickerUrl(sticker == null ? "" : sticker.getImageUrl());
        row.setPinned(false);
        row.setLikeCount(0);
        row.setDislikeCount(0);
        row.setDeleted(false);
        comments.insert(row);
        int order = 0;
        for (UploadAsset asset : mediaRows) {
            CommentMedia media = new CommentMedia();
            media.setCommentId(row.getId()); media.setAssetId(asset.getId()); media.setUrl(asset.getUrl());
            media.setWidth(asset.getWidth()); media.setHeight(asset.getHeight()); media.setSortOrder(order++);
            commentMedia.insert(media);
            assets.update(null, new LambdaUpdateWrapper<UploadAsset>().eq(UploadAsset::getId, asset.getId()).set(UploadAsset::getAttached, true));
        }
        changePostCount(postId, 1);
        inbox.deliver(post.getAuthorId(),userId,"reply",postId,row.getId(),"comment:"+row.getId());
        if(target!=null)inbox.deliver(target.getAuthorId(),userId,"reply",postId,row.getId(),"comment:"+row.getId());
        levels.award(userId,"comment","comment:"+row.getId());
        return view(comments.selectById(row.getId()), post, userId, false);
    }

    @Override
    @Transactional
    public ReactionResult react(Long postId, Long commentId, Long userId, String requested) {
        var recipient=comments.selectById(commentId);
        levels.lockParticipants(userId,recipient==null?null:recipient.getAuthorId());
        userAccess.requireForUpdate(userId);
        if (!"like".equals(requested)) throw new BizException("评论互动类型不合法");
        posts.requirePost(postId, true);
        Comment comment = comments.selectOne(new LambdaQueryWrapper<Comment>().eq(Comment::getId, commentId).last("FOR UPDATE"));
        if (comment == null || !Objects.equals(comment.getPostId(), postId) || Boolean.TRUE.equals(comment.getDeleted()))
            throw new BizException("评论不存在或已删除");
        CommentReaction existing = reactions.selectOne(new LambdaQueryWrapper<CommentReaction>()
            .eq(CommentReaction::getCommentId, commentId).eq(CommentReaction::getUserId, userId).last("FOR UPDATE"));
        String active;
        if (existing != null && requested.equals(existing.getReaction())) {
            reactions.deleteById(existing.getId());
            adjustLike(commentId, -1);
            active = null;
        } else if (existing != null) {
            existing.setReaction(requested); reactions.updateById(existing);
            // A legacy dislike is an unselected like; it never reduces the like count.
            adjustLike(commentId, 1);
            active = requested;
        } else {
            CommentReaction row = new CommentReaction(); row.setCommentId(commentId); row.setUserId(userId); row.setReaction(requested);
            reactions.insert(row); adjustLike(commentId, 1); active = requested;
        }
        if("like".equals(active)&&!Objects.equals(userId,comment.getAuthorId()))
            levels.award(comment.getAuthorId(),"received_like","comment:"+commentId+":user:"+userId);
        Comment updated = comments.selectById(commentId);
        if("like".equals(active))inbox.deliver(comment.getAuthorId(),userId,"comment_like",postId,commentId,"comment-like:"+commentId+":"+userId);
        return new ReactionResult(active, safe(updated.getLikeCount()));
    }

    @Override
    @Transactional
    public DeleteResult delete(Long postId, Long commentId, Long userId) {
        cleanup.lock();
        userAccess.requireForUpdate(userId);
        Post post = posts.requirePost(postId, true);
        Comment comment = comments.selectOne(new LambdaQueryWrapper<Comment>().eq(Comment::getId, commentId).last("FOR UPDATE"));
        if (comment == null || !Objects.equals(comment.getPostId(), postId) || Boolean.TRUE.equals(comment.getDeleted()))
            throw new BizException("评论不存在或已删除");
        if (!Objects.equals(comment.getAuthorId(), userId) && !Objects.equals(post.getAuthorId(), userId))
            throw new BizException("无权删除该评论");
        boolean root = comment.getParentId() == null;
        List<Comment> activeReplies = root ? comments.selectList(activeReplies(postId, commentId)) : List.of();
        boolean cascade = root && cascadeDelete();
        if (root && !cascade && !activeReplies.isEmpty()) {
            removeAssets(List.of(commentId));
            reactions.delete(new LambdaQueryWrapper<CommentReaction>().eq(CommentReaction::getCommentId, commentId));
            comments.update(null, new LambdaUpdateWrapper<Comment>().eq(Comment::getId, commentId)
                .set(Comment::getContent, "").set(Comment::getContentType, "text")
                .set(Comment::getStickerId, null).set(Comment::getStickerUrl, "")
                .set(Comment::getLikeCount, 0).set(Comment::getDislikeCount, 0)
                .set(Comment::getDeleted, true).set(Comment::getDeletedTime, LocalDateTime.now()));
            changePostCount(postId, -1);
            return new DeleteResult(1,true);
        }
        List<Long> ids = new ArrayList<>(); ids.add(commentId);
        if (cascade) ids.addAll(activeReplies.stream().map(Comment::getId).toList());
        removeAssets(ids);
        reactions.delete(new LambdaQueryWrapper<CommentReaction>().in(CommentReaction::getCommentId, ids));
        comments.deleteById(commentId);
        if (!root) purgeEmptyTombstone(postId, comment.getParentId());
        changePostCount(postId, -ids.size());
        return new DeleteResult(ids.size(),false);
    }

    private LambdaQueryWrapper<Comment> rootFilter(Post post, String scope) {
        LambdaQueryWrapper<Comment> q = new LambdaQueryWrapper<Comment>().eq(Comment::getPostId, post.getId()).isNull(Comment::getParentId);
        q.and(w -> w.eq(Comment::getDeleted, false).or().apply(
            "EXISTS (SELECT 1 FROM `comment` active_reply WHERE active_reply.post_id=" + post.getId()
                + " AND active_reply.parent_id=`comment`.id AND active_reply.deleted=0)"));
        if ("author".equals(scope)) {
            if (post.getAuthorId() == null) q.apply("1=0");
            else q.and(w -> w.eq(Comment::getAuthorId, post.getAuthorId()).or().inSql(Comment::getId,
                "SELECT parent_id FROM `comment` WHERE post_id=" + post.getId() + " AND parent_id IS NOT NULL AND deleted=0 AND author_id=" + post.getAuthorId()));
        }
        return q;
    }

    private void purgeEmptyTombstone(Long postId, Long rootId) {
        if (rootId == null || comments.selectCount(activeReplies(postId, rootId)) != 0) return;
        comments.delete(new LambdaQueryWrapper<Comment>().eq(Comment::getId, rootId).eq(Comment::getPostId, postId)
            .isNull(Comment::getParentId).eq(Comment::getDeleted, true));
    }

    private LambdaQueryWrapper<Comment> activeReplies(Long postId, Long rootId) {
        return new LambdaQueryWrapper<Comment>().eq(Comment::getPostId, postId).eq(Comment::getParentId, rootId).eq(Comment::getDeleted, false);
    }

    private CommentVO view(Comment c, Post post, Long userId, boolean contextOnly) {
        return view(c,post,userId,contextOnly,null);
    }
    private record ViewData(Map<Long,AppUser> authors,Map<Long,Comment> targets,
                            Map<Long,List<CommentMedia>> media,Map<Long,CommentReaction> reactions) {}
    private CommentVO view(Comment c, Post post, Long userId, boolean contextOnly, ViewData data) {
        CommentVO vo = new CommentVO();
        vo.setId(String.valueOf(c.getId())); vo.setPostId(String.valueOf(c.getPostId()));
        vo.setParentId(c.getParentId() == null ? null : String.valueOf(c.getParentId()));
        vo.setReplyToId(c.getReplyToId() == null ? null : String.valueOf(c.getReplyToId()));
        vo.setReplyToNickname(orEmpty(c.getReplyToNickname()));
        if (c.getReplyToId() != null) {
            var target = data == null ? comments.selectById(c.getReplyToId()) : data.targets().get(c.getReplyToId());
            var targetAuthor = target == null || target.getAuthorId() == null ? null : data == null ? users.selectById(target.getAuthorId()) : data.authors().get(target.getAuthorId());
            if (targetAuthor != null && targetAuthor.hasBeenDeleted()) vo.setReplyToNickname("已注销用户");
        }
        vo.setDeleted(Boolean.TRUE.equals(c.getDeleted())); vo.setContextOnly(contextOnly);
        vo.setPinned(Boolean.TRUE.equals(c.getPinned()));
        vo.setCreateTime(c.getCreateTime() == null ? "" : c.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        vo.setReplyCount(0);
        if (vo.getDeleted()) {
            vo.setAuthorAvatarFrame(""); vo.setAuthorNickname(""); vo.setAuthorLevel(0); vo.setAuthorAvatar(""); vo.setRegion(""); vo.setContent("");
            vo.setContentType("text"); vo.setStickerUrl(""); vo.setLikeCount(0);
            vo.setCanDelete(false); vo.setMedia(List.of()); vo.setReplies(List.of()); return vo;
        }
        vo.setAuthorId(c.getAuthorId() == null ? null : String.valueOf(c.getAuthorId()));
        vo.setAuthorNickname(c.getAuthorNickname()); vo.setAuthorLevel(c.getAuthorLevel()); vo.setRegion(orEmpty(c.getRegion()));
        vo.setContent(orEmpty(c.getContent())); vo.setContentType(c.getContentType()); vo.setStickerId(c.getStickerId()); vo.setStickerUrl(orEmpty(c.getStickerUrl()));
        vo.setLikeCount(safe(c.getLikeCount()));
        AppUser author = c.getAuthorId() == null ? null : data == null ? users.selectById(c.getAuthorId()) : data.authors().get(c.getAuthorId());
        if(author!=null&&!author.hasBeenDeleted())vo.setAuthorLevel(author.getLevel());
        vo.setAuthorAvatarFrame(author == null || author.hasBeenDeleted() ? "" : orEmpty(author.getAvatarFrame()));
        vo.setAuthorAvatarFrameExpiresAt(author == null || author.hasBeenDeleted() ? null : author.getAvatarFrameExpiresAt());
        vo.setAuthorAvatar(author == null || author.hasBeenDeleted() ? "" : orEmpty(author.getAvatar()));
        if (author != null && author.hasBeenDeleted()) {
            vo.setAuthorNickname("已注销用户"); vo.setAuthorLevel(0); vo.setRegion("");
        }
        vo.setCanDelete(userId != null && (Objects.equals(c.getAuthorId(), userId) || post != null && Objects.equals(post.getAuthorId(), userId)));
        if (userId != null) {
            CommentReaction reaction = data == null ? reactions.selectOne(new LambdaQueryWrapper<CommentReaction>()
                .eq(CommentReaction::getCommentId, c.getId()).eq(CommentReaction::getUserId, userId)) : data.reactions().get(c.getId());
            vo.setViewerReaction(reaction != null && "like".equals(reaction.getReaction()) ? "like" : null);
        }
        var mediaRows = data == null ? commentMedia.selectList(new LambdaQueryWrapper<CommentMedia>().eq(CommentMedia::getCommentId, c.getId())
            .orderByAsc(CommentMedia::getSortOrder, CommentMedia::getId)) : data.media().getOrDefault(c.getId(),List.of());
        vo.setMedia(mediaRows.stream()
            .map(m -> new CommentVO.Media(m.getId(), m.getAssetId(), m.getUrl(), m.getWidth(), m.getHeight(), m.getSortOrder())).toList());
        return vo;
    }

    private List<UploadAsset> resolveAssets(Long userId, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Map<Long,UploadAsset> found = assets.selectBatchIds(ids).stream().collect(Collectors.toMap(UploadAsset::getId, a -> a));
        List<UploadAsset> ordered = new ArrayList<>();
        for (Long id : ids) {
            UploadAsset a = found.get(id);
            if (a == null || !Objects.equals(a.getOwnerId(), userId) || !"image".equals(a.getMediaType())
                || !"ready".equals(a.getStatus()) || Boolean.TRUE.equals(a.getAttached()))
                throw new BizException("评论图片不存在、未处理完成或不属于当前用户");
            ordered.add(a);
        }
        return ordered;
    }

    private void removeAssets(Collection<Long> commentIds) {
        if (commentIds.isEmpty()) return;
        for(Long id : commentIds) cleanup.track("comment",id);
        List<CommentMedia> links = commentMedia.selectList(new LambdaQueryWrapper<CommentMedia>().in(CommentMedia::getCommentId, commentIds));
        if (links.isEmpty()) return;
        List<Long> assetIds = links.stream().map(CommentMedia::getAssetId).toList();
        commentMedia.delete(new LambdaQueryWrapper<CommentMedia>().in(CommentMedia::getCommentId, commentIds));
        assetIds.forEach(cleanup::retireAsset);
        // The transactional cleanup outbox handles files after commit, including retries.
    }

    private void adjustLike(Long id, int delta) {
        comments.update(null, new LambdaUpdateWrapper<Comment>().eq(Comment::getId, id)
            .setSql("like_count=GREATEST(0,like_count" + (delta > 0 ? "+1" : "-1") + ")"));
    }

    private void changePostCount(Long postId, int delta) {
        postMapper.update(null, new LambdaUpdateWrapper<Post>().eq(Post::getId, postId)
            .setSql("comment_count=GREATEST(0,comment_count" + (delta >= 0 ? "+" : "") + delta + ")"));
    }

    private int imageMax() { return Math.max(0, Math.min(9, integerValue("comment_image_max", 3))); }
    private boolean cascadeDelete() { return "1".equals(value("comment_delete_cascade", "0")); }
    private int integerValue(String key,int fallback) { try { return Integer.parseInt(value(key,String.valueOf(fallback))); } catch (Exception e) { return fallback; } }
    private String value(String key,String fallback) { SystemConfig row = configs.selectById(key); return row == null ? fallback : row.getConfigValue(); }
    private static int safe(Integer value) { return value == null ? 0 : Math.max(0, value); }
    private static String trim(String value) { return value == null ? "" : value.strip(); }
    private static String orEmpty(String value) { return value == null ? "" : value; }
    private static String limit(String value,int max) { return value.codePointCount(0,value.length()) <= max ? value : value.substring(0,value.offsetByCodePoints(0,max)); }
    private static List<Long> distinct(List<Long> ids) { return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList(); }
    private static void afterCommit(Consumer<Void> action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { action.accept(null); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.accept(null); }
        });
    }
}
