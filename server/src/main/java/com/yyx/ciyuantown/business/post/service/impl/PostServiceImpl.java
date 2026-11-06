package com.yyx.ciyuantown.business.post.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.post.dto.PostQueryParam;
import com.yyx.ciyuantown.business.post.entity.*;
import com.yyx.ciyuantown.business.post.mapper.*;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.business.post.vo.*;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.business.homeconfig.mapper.HomeChannelMapper;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.publish.mapper.PostTopicMapper;
import com.yyx.ciyuantown.business.publish.entity.PostTopic;
import com.yyx.ciyuantown.business.interaction.mapper.UserFollowMapper;
import com.yyx.ciyuantown.business.interaction.entity.UserFollow;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.support.*;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {
    private final com.yyx.ciyuantown.business.interaction.service.SocialInboxService inbox;
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
    private final com.yyx.ciyuantown.business.level.LevelService levels;
    private final PostMapper posts;
    private final PostMediaMapper media;
    private final PostTagMapper tags;
    private final HomeChannelMapper channels;
    private final AppUserMapper users;
    private final ObjectMapper json;
    private final RedisHotspotService redis;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final PostTopicMapper postTopics;
    private final UserFollowMapper follows;
    private final com.yyx.ciyuantown.business.publish.mapper.UploadAssetMapper assets;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional(readOnly=true)
    @Cacheable(cacheNames="post-list",key="'author-profile-v2:'+#publishedOnly+':'+#q.pageNum+':'+#q.pageSize+':'+#q.status+':'+#q.keyword+':'+#q.channelId")
    public Page<PostListVO> list(PostQueryParam q, boolean publishedOnly) {
        return list(q,publishedOnly,null);
    }
    @Override
    @Transactional(readOnly=true)
    public Page<PostListVO> listPublicByAuthor(PostQueryParam q,Long authorId) {
        return list(q,true,authorId);
    }
    @Override @Transactional(readOnly=true)
    public Page<PostListVO> listMine(PostQueryParam q,Long authorId) {
        if(authorId==null)throw new BizException("请先登录");
        return list(q,false,authorId);
    }
    @Override @Transactional(readOnly=true)
    public Page<PostListVO> searchMine(PostQueryParam q,Long userId,String scope) {
        if(userId==null || userId<1)throw new BizException("请先登录");
        q.validate();
        if(!Set.of("publish","favorite","like").contains(scope))throw new BizException("无效的搜索范围");
        var page=new Page<PostListVO>(q.getPageNum(),q.getPageSize(),0);
        if(q.getKeyword()==null || q.getKeyword().isBlank())return page.setRecords(List.of());
        var filter=new LambdaQueryWrapper<Post>();
        // Scope relations are restricted to the authenticated user.
        String favorite="EXISTS (SELECT 1 FROM user_favorite f WHERE f.post_id=post.id AND f.user_id={0})";
        String like="EXISTS (SELECT 1 FROM user_like l WHERE l.post_id=post.id AND l.user_id={0})";
        switch(scope) {
            case "publish" -> filter.eq(Post::getAuthorId,userId).in(Post::getStatus,0,1);
            case "favorite" -> filter.eq(Post::getStatus,1).apply(favorite,userId);
            case "like" -> filter.eq(Post::getStatus,1).apply(like,userId);
            default -> throw new BizException("无效的搜索范围");
        }
        String pattern="%"+q.getKeyword().trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        filter.and(w -> w.apply("title LIKE {0} ESCAPE '!'",pattern).or().apply("content LIKE {0} ESCAPE '!'",pattern));
        if(q.getChannelId()!=null)filter.eq(Post::getChannelId,q.getChannelId());
        long total=posts.selectCount(filter);page.setTotal(total);
        if(q.offset()>=total)return page.setRecords(List.of());
        filter.orderByDesc(Post::getCreateTime,Post::getId).last("LIMIT "+q.offset()+","+q.getPageSize());
        return page.setRecords(toViews(posts.selectList(filter)));
    }
    @Override @Transactional(readOnly=true)
    public Page<PostListVO> listFollowing(PostQueryParam q,Long userId){
        if(userId==null||userId<1)throw new BizException("请先登录");q.validate();
        var filter=new LambdaQueryWrapper<Post>().eq(Post::getStatus,1).inSql(Post::getAuthorId,"SELECT followee_id FROM user_follow WHERE follower_id="+userId);
        if(q.getChannelId()!=null)filter.eq(Post::getChannelId,q.getChannelId());long total=posts.selectCount(filter);var page=new Page<PostListVO>(q.getPageNum(),q.getPageSize(),total);if(q.offset()>=total)return page.setRecords(List.of());filter.orderByDesc(Post::getCreateTime,Post::getId).last("LIMIT "+q.offset()+","+q.getPageSize());return page.setRecords(toViews(posts.selectList(filter)));
    }
    @Override @Transactional(readOnly=true)
    public Page<PostListVO> listFavorites(PostQueryParam q, Long userId) {
        return listByRelation(q, userId, "user_favorite", "r.create_time DESC,r.id DESC");
    }
    @Override @Transactional(readOnly=true)
    public Page<PostListVO> listLikes(PostQueryParam q, Long userId) {
        return listByRelation(q, userId, "user_like", "r.create_time DESC,r.id DESC");
    }
    @Override @Transactional(readOnly=true)
    public Page<PostListVO> listHistory(PostQueryParam q, Long userId) {
        return listByRelation(q, userId, "user_post_history", "r.viewed_at DESC,r.post_id DESC");
    }
    @Override @Transactional
    public void recordView(Long postId, Long userId) {
        requirePost(postId, true);
        jdbc.update("""
            INSERT INTO user_post_history (user_id, post_id, viewed_at)
            SELECT ?, id, CURRENT_TIMESTAMP(6) FROM post WHERE id=? AND status=1
            ON DUPLICATE KEY UPDATE viewed_at=GREATEST(user_post_history.viewed_at, CURRENT_TIMESTAMP(6))
            """, userId, postId);
    }

    private Page<PostListVO> listByRelation(PostQueryParam q, Long userId, String table, String order) {
        q.validate();
        List<Object> args = new ArrayList<>(); args.add(userId);
        String filter = " FROM " + table + " r JOIN post p ON p.id=r.post_id WHERE r.user_id=? AND p.status=1";
        if (q.getChannelId()!=null) { filter += " AND p.channel_id=?"; args.add(q.getChannelId()); }
        long total = jdbc.queryForObject("SELECT COUNT(*)"+filter, Long.class, args.toArray());
        var page = new Page<PostListVO>(q.getPageNum(), q.getPageSize(), total);
        if (q.offset()>=total) return page.setRecords(List.of());
        args.add(q.offset()); args.add(q.getPageSize());
        var ids = jdbc.queryForList("SELECT p.id"+filter+" ORDER BY "+order+" LIMIT ?,?", Long.class, args.toArray());
        if (ids.isEmpty()) return page.setRecords(List.of());
        Map<Long,Post> byId = posts.selectBatchIds(ids).stream().collect(Collectors.toMap(Post::getId,p->p));
        return page.setRecords(toViews(ids.stream().map(byId::get).filter(Objects::nonNull).toList()));
    }
    @Override
    @Transactional(readOnly = true)
    public Page<PostListVO> search(PostQueryParam q, String type, boolean hot) {
        q.validate();
        var page = new Page<PostListVO>(q.getPageNum(), q.getPageSize(), 0);
        if (q.getKeyword() == null || q.getKeyword().isBlank()) return page;
        var filter = searchFilter(q, true);
        switch (type) {
            case "image", "video" -> filter.eq(Post::getMode, type);
            case "post" -> filter.and(w -> w.eq(Post::getMode, "post").or().isNull(Post::getMode));
            case "official" -> filter.inSql(Post::getAuthorId,
                "SELECT u.id FROM app_user u JOIN admin_user a ON a.id = u.official_admin_id " +
                "WHERE u.deleted = 0 AND u.status = 0 AND a.deleted = 0 AND a.status = 0 AND a.user_type = 1");
            case "all" -> { }
            default -> throw new BizException("无效的搜索类型");
        }
        long total = posts.selectCount(filter);
        page.setTotal(total);
        if (q.offset() >= total) return page;
        if (hot) filter.orderByDesc(Post::getLikeCount, Post::getCommentCount);
        filter.orderByDesc(Post::getCreateTime, Post::getId).last("LIMIT " + q.offset() + "," + q.getPageSize());
        return page.setRecords(toViews(posts.selectList(filter)));
    }

    @Override
    public List<String> suggestTitles(PostQueryParam q) {
        q.validate();
        if (q.getKeyword() == null || q.getKeyword().isBlank()) return List.of();
        return posts.selectList(searchFilter(q, false).select(Post::getTitle)
            .groupBy(Post::getTitle).orderByAsc(Post::getTitle).last("LIMIT 6"))
            .stream().map(Post::getTitle).toList();
    }

    private LambdaQueryWrapper<Post> searchFilter(PostQueryParam q, boolean includeContent) {
        String keyword = q.getKeyword().trim();
        String pattern = "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var filter = new LambdaQueryWrapper<Post>().eq(Post::getStatus, 1)
            .eq(q.getChannelId() != null, Post::getChannelId, q.getChannelId());
        return filter.and(w -> {
            w.apply("title LIKE {0} ESCAPE '!'", pattern);
            if (includeContent) w.or().apply("content LIKE {0} ESCAPE '!'", pattern);
        });
    }
    private Page<PostListVO> list(PostQueryParam q,boolean publishedOnly,Long authorId) {
        q.validate();
        if(publishedOnly&&authorId==null){
            String feedKey=q.getChannelId()==null?"ct:feed:global":"ct:feed:channel:"+q.getChannelId();
            var feed=redis.feedPage(feedKey,q.offset(),q.getPageSize());
            if(feed.isPresent()){
                var fp=feed.get();
                var page=new Page<PostListVO>(q.getPageNum(),q.getPageSize(),fp.total());
                if(fp.ids().isEmpty())return page.setRecords(List.of());
                Map<Long,Post> byId=posts.selectBatchIds(fp.ids()).stream().filter(p->p.getStatus()==1).collect(Collectors.toMap(Post::getId,p->p));
                if(byId.size()==fp.ids().size())return page.setRecords(toViews(fp.ids().stream().map(byId::get).toList()));
            }
        }
        var filter = new LambdaQueryWrapper<Post>();
        if (publishedOnly) filter.eq(Post::getStatus, 1);
        else if(authorId!=null)filter.in(Post::getStatus,0,1);
        else if (q.getStatus()!=null) filter.eq(Post::getStatus, q.getStatus());
        if(authorId!=null)filter.eq(Post::getAuthorId,authorId);
        if (!publishedOnly && authorId==null && q.getKeyword()!=null && !q.getKeyword().isBlank()) {
            String keyword=q.getKeyword().trim();
            filter.and(w -> w.like(Post::getTitle,keyword).or().like(Post::getContent,keyword));
        }
        if(q.getChannelId()!=null)filter.eq(Post::getChannelId,q.getChannelId());
        long total=posts.selectCount(filter);
        var page=new Page<PostListVO>(q.getPageNum(),q.getPageSize(),total);
        if (q.offset()>=total) return page.setRecords(List.of());
        if (authorId != null) filter.orderByDesc(Post::getProfilePinnedAt);
        // offset、size 均为已校验的整数；无需额外SQL解析器依赖。
        filter.orderByDesc(Post::getCreateTime,Post::getId).last("LIMIT "+q.offset()+","+q.getPageSize());
        return page.setRecords(toViews(posts.selectList(filter)));
    }
    private List<PostListVO> toViews(List<Post> rows) {
        if (rows.isEmpty()) return List.of();
        var ids=rows.stream().map(Post::getId).toList();
        var assetIds=media.selectList(new LambdaQueryWrapper<PostMedia>().in(PostMedia::getPostId,ids)).stream().map(PostMedia::getAssetId).filter(Objects::nonNull).collect(Collectors.toSet());
        var assetMap=assetIds.isEmpty()?Map.<Long,com.yyx.ciyuantown.business.publish.entity.UploadAsset>of():assets.selectBatchIds(assetIds).stream().collect(Collectors.toMap(a->a.getId(),a->a));
        var mediaByPost=media.selectList(new LambdaQueryWrapper<PostMedia>().in(PostMedia::getPostId,ids)
            .orderByAsc(PostMedia::getSortOrder,PostMedia::getId)).stream().collect(Collectors.groupingBy(PostMedia::getPostId));
        var tagsByPost=tags.selectList(new LambdaQueryWrapper<PostTag>().in(PostTag::getPostId,ids)
            .orderByAsc(PostTag::getSortOrder,PostTag::getId)).stream().collect(Collectors.groupingBy(PostTag::getPostId));
        var channelIds=rows.stream().map(Post::getChannelId).filter(Objects::nonNull).collect(Collectors.toSet());
        var channelNames=channelIds.isEmpty()?Map.<Long,String>of():channels.selectBatchIds(channelIds).stream().collect(Collectors.toMap(c->c.getId(),c->c.getName()));
        var authorIds=rows.stream().map(Post::getAuthorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long,Long> publicUids=new HashMap<>();Map<Long,String> authorAvatars=new HashMap<>();
        Map<Long,String> authorAvatarFrames=new HashMap<>();
        Map<Long,Long> frameExpiries=new HashMap<>();
        Map<Long,String> authorNicknames=new HashMap<>();Map<Long,Integer> authorLevels=new HashMap<>();
        Set<Long> deletedAuthors=new HashSet<>();
        if(!authorIds.isEmpty())for(var user:users.selectBatchIds(authorIds)){
            if(user.hasBeenDeleted()) {
                deletedAuthors.add(user.getId());
                authorNicknames.put(user.getId(),"已注销用户");
                if(user.getPublicUid()!=null)publicUids.put(user.getId(),user.getPublicUid());
                continue;
            }
            authorAvatarFrames.put(user.getId(),user.getAvatarFrame()==null?"":user.getAvatarFrame());
            if(user.getAvatarFrameExpiresAt()!=null)frameExpiries.put(user.getId(),user.getAvatarFrameExpiresAt());
            if(user.getNickname()!=null&&!user.getNickname().isBlank())authorNicknames.put(user.getId(),user.getNickname());
            if(user.getLevel()!=null)authorLevels.put(user.getId(),user.getLevel());
            if(user.getPublicUid()!=null)publicUids.put(user.getId(),user.getPublicUid());
            if(user.getAvatar()!=null&&!user.getAvatar().isBlank())authorAvatars.put(user.getId(),user.getAvatar());
        }
        return rows.stream().map(p -> {
            var vo=new PostListVO();
            BeanUtils.copyProperties(p,vo);
            vo.setPinned(p.getProfilePinnedAt() != null);
            vo.setId(p.getId().toString());
            vo.setRepostOfPostId(p.getRepostOfPostId()==null?null:p.getRepostOfPostId().toString());
            if(p.getRepostOfPostId()!=null){
                Post original=posts.selectById(p.getRepostOfPostId());
                boolean available=original!=null&&original.getStatus()==1;
                String label=!available?"原帖不存在或已下架":!original.getTitle().isBlank()?original.getTitle():!original.getContent().isBlank()?original.getContent():original.getCreateTime().format(TIME);
                var cover=available?media.selectList(new LambdaQueryWrapper<PostMedia>().eq(PostMedia::getPostId,original.getId()).orderByAsc(PostMedia::getSortOrder).last("LIMIT 1")):List.<PostMedia>of();
                vo.setRepost(new PostListVO.RepostSummary(p.getRepostOfPostId().toString(),available?original.getAuthorNickname():"",label,cover.isEmpty()?"":cover.getFirst().getCoverUrl()==null||cover.getFirst().getCoverUrl().isBlank()?cover.getFirst().getUrl():cover.getFirst().getCoverUrl(),available));
            }
            vo.setAuthorId(p.getAuthorId()==null?null:p.getAuthorId().toString());
            // 历史帖子保留发布时快照；展示时优先使用作者当前昵称。
            vo.setAuthorNickname(authorNicknames.getOrDefault(p.getAuthorId(),p.getAuthorNickname()));
            vo.setAuthorLevel(deletedAuthors.contains(p.getAuthorId())?0:authorLevels.getOrDefault(p.getAuthorId(),p.getAuthorLevel()));
            Long publicUid=p.getAuthorId()==null?null:publicUids.get(p.getAuthorId());
            vo.setAuthorPublicUid(publicUid==null?null:publicUid.toString());
            vo.setAuthorAvatar(p.getAuthorId()==null?null:authorAvatars.get(p.getAuthorId()));
            vo.setAuthorAvatarFrame(authorAvatarFrames.getOrDefault(p.getAuthorId(),""));
            vo.setAuthorAvatarFrameExpiresAt(frameExpiries.get(p.getAuthorId()));
            vo.setMode(p.getMode());vo.setChannelId(p.getChannelId());vo.setChannelName(p.getChannelId()==null?"":channelNames.getOrDefault(p.getChannelId(),""));
            vo.setAllowDownload(p.getAllowDownload());vo.setDownloadWatermark(p.getDownloadWatermark());vo.setOriginal(p.getOriginal());
            if(p.getContentBlocks()!=null&&!p.getContentBlocks().isBlank())try{vo.setContentBlocks(json.readTree(p.getContentBlocks()));}catch(Exception ignored){vo.setContentBlocks(null);}
            vo.setDate(p.getCreateTime().format(DATE));
            vo.setCreateTime(p.getCreateTime().format(TIME));
            vo.setMedia(mediaByPost.getOrDefault(p.getId(),List.of()).stream()
                .map(m -> {var a=m.getAssetId()==null?null:assetMap.get(m.getAssetId());Object qualities=List.of();if(a!=null&&a.getQualitiesJson()!=null)try{qualities=json.readTree(a.getQualitiesJson());}catch(Exception ignored){}return new PostListVO.MediaItem(m.getId(),m.getAssetId(),m.getType(),m.getLayout(),m.getAspectRatio(),m.getWidth(),m.getHeight(),m.getDurationMs(),m.getDuration(),a==null?m.getUrl():a.getUrl(),a==null?m.getCoverUrl():a.getCoverUrl(),a==null?m.getUrl():a.getPreviewUrl(),a==null?m.getUrl():a.getOriginalUrl(),a==null?"":a.getHlsUrl(),qualities,a==null?"legacy":a.getResourceVersion(),a==null?"ready":a.getStatus(),a==null?"":a.getErrorMessage());}).toList());
            vo.setTags(tagsByPost.getOrDefault(p.getId(),List.of()).stream().map(PostTag::getName).toList());
            return vo;
        }).toList();
    }
    @Override
    public Post requirePost(Long id, boolean publishedOnly) {
        if (id==null || id<1) throw new BizException("帖子ID必须为正数");
        Post p=posts.selectById(id);
        if (p==null || (publishedOnly && p.getStatus()!=1)) throw new BizException("帖子不存在或已下架");
        return p;
    }
    @Override
    @Transactional(readOnly=true)
    @Cacheable(cacheNames="post-detail",key="'author-profile-v3:'+#publishedOnly+':'+#id",condition="#publishedOnly")
    public PostDetailVO detail(Long id, boolean publishedOnly) {
        var p=requirePost(id,publishedOnly);
        var vo=new PostDetailVO();
        BeanUtils.copyProperties(p,vo);
        BeanUtils.copyProperties(toViews(List.of(p)).getFirst(),vo);
        var author = p.getAuthorId()==null ? null : users.selectById(p.getAuthorId());
        if(author!=null && author.hasBeenDeleted())vo.setAuthorBio("");
        return vo;
    }
    @Override
    @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail","home-config"},allEntries=true)
    public void updateStatus(Long id, Integer status) {
        if (status==null || status<0 || status>2) throw new BizException("状态仅允许0、1、2");
        Post existing=requirePost(id,false);
        if(status==1&&existing.getAuthorId()!=null)levels.lockParticipants(existing.getAuthorId(),null);
        var topicIds=postTopics.selectList(new LambdaQueryWrapper<PostTopic>().eq(PostTopic::getPostId,id)).stream().map(PostTopic::getTopicId).toList();
        var followerIds=follows.selectList(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFolloweeId,existing.getAuthorId())).stream().map(UserFollow::getFollowerId).toList();
        var p=new Post(); p.setId(id); p.setStatus(status);
        if (posts.updateById(p)!=1) throw new BizException("帖子不存在或已下架");
        if(status==1)levels.award(existing.getAuthorId(),"post","post:"+id);
        if(status==1){existing.setStatus(1);inbox.published(existing);}
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){boolean add=status==1;long score=existing.getCreateTime()==null?System.currentTimeMillis():existing.getCreateTime().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();redis.feed("ct:feed:global",score,id,add);redis.feed("ct:feed:channel:"+existing.getChannelId(),score,id,add);for(Long topicId:topicIds)redis.feed("ct:feed:topic:"+topicId,score,id,add);for(Long followerId:followerIds)redis.feed("ct:feed:following:"+followerId,score,id,add);}});
    }
    @Override
    @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail","home-config"},allEntries=true)
    public void delete(Long id) {
        deletePost(requirePost(id, false));
    }
    @Override
    @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail","home-config"},allEntries=true)
    public void deleteOwned(Long id, Long authorId) {
        deletePost(lockOwnedPost(id, authorId));
    }
    @Override
    @Transactional
    public void setProfilePinned(Long id, Long authorId, boolean pinned) {
        Post post = lockOwnedPost(id, authorId);
        if (pinned && post.getStatus() != 1) throw new BizException("帖子发布后才可以置顶");
        if (pinned == (post.getProfilePinnedAt() != null)) return;
        posts.update(null, new LambdaUpdateWrapper<Post>().eq(Post::getId, id)
            .set(Post::getProfilePinnedAt, pinned ? LocalDateTime.now() : null));
    }
    @Override
    @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail","home-config"},allEntries=true)
    public void editOwnedText(Long id, Long authorId, String title, String content) {
        Post post = lockOwnedPost(id, authorId);
        if (title == null || content == null) throw new BizException("请填写标题和正文");
        if (title.codePointCount(0, title.length()) > 100) throw new BizException("标题最多100个字符");
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535)
            throw new BizException("正文超出长度限制");
        var update = new LambdaUpdateWrapper<Post>().eq(Post::getId, id)
            .set(Post::getTitle, title).set(Post::getContent, content);
        if (post.getContentBlocks() != null && !content.equals(post.getContent() == null ? "" : post.getContent())) {
            update.set(Post::getContentBlocks, replaceBodyText(post.getContentBlocks(), content));
        }
        posts.update(null, update);
    }
    private Post lockOwnedPost(Long id, Long authorId) {
        if (authorId == null) throw new BizException("请先登录");
        Post post = posts.selectOne(new LambdaQueryWrapper<Post>().eq(Post::getId, id)
            .eq(Post::getAuthorId, authorId).last("FOR UPDATE"));
        if (post == null) throw new BizException("帖子不存在或无权操作");
        return post;
    }
    private String replaceBodyText(String original, String content) {
        try {
            var blocks = json.readTree(original);
            if (!blocks.isArray()) throw new BizException("正文格式不合法");
            var updated = json.createArrayNode();
            var paragraph = json.createObjectNode();
            paragraph.put("type", "paragraph");
            paragraph.putArray("runs").addObject().put("type", "text").put("text", content);
            boolean inserted = false;
            for (var block : blocks) {
                if (!"paragraph".equals(block.path("type").asText())) {
                    updated.add(block);
                } else if (!inserted) {
                    if (!content.isEmpty()) updated.add(paragraph);
                    inserted = true;
                }
            }
            if (!inserted && !content.isEmpty()) updated.insert(0, paragraph);
            return json.writeValueAsString(updated);
        } catch (JsonProcessingException e) {
            throw new BizException("正文格式不合法");
        }
    }
    private void deletePost(Post existing) {
        Long id = existing.getId();
        cleanup.track("post",id);
        var topicIds=postTopics.selectList(new LambdaQueryWrapper<PostTopic>().eq(PostTopic::getPostId,id)).stream().map(PostTopic::getTopicId).toList();
        var followerIds=follows.selectList(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFolloweeId,existing.getAuthorId())).stream().map(UserFollow::getFollowerId).toList();
        // 外键级联在同一事务中删除媒体、标签、顶级评论和回复。
        if (posts.deleteById(id)!=1) throw new BizException("帖子不存在或已下架");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){redis.feed("ct:feed:global",0,id,false);redis.feed("ct:feed:channel:"+existing.getChannelId(),0,id,false);for(Long topicId:topicIds)redis.feed("ct:feed:topic:"+topicId,0,id,false);for(Long followerId:followerIds)redis.feed("ct:feed:following:"+followerId,0,id,false);}});
    }
    @Override
    @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail","home-config"},allEntries=true)
    public void updateHomeNews(Long id, Boolean homeNewsEnabled) {
        if (homeNewsEnabled==null) throw new BizException("首页资讯轮播设置不能为空");
        requirePost(id,false);
        var row=new Post(); row.setId(id); row.setHomeNewsEnabled(homeNewsEnabled);
        if (posts.updateById(row)!=1) throw new BizException("帖子不存在或已下架");
    }
}
