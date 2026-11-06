package com.yyx.ciyuantown.business.interaction.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.interaction.service.InteractionService;
import com.yyx.ciyuantown.business.interaction.vo.InteractionResultVO;
import com.yyx.ciyuantown.business.post.entity.Post;
import com.yyx.ciyuantown.business.post.mapper.PostMapper;
import com.yyx.ciyuantown.business.post.vo.PostDetailVO;
import com.yyx.ciyuantown.common.api.ResultCode;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import com.yyx.ciyuantown.business.interaction.entity.UserFollow;
import com.yyx.ciyuantown.business.interaction.mapper.UserFollowMapper;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import com.yyx.ciyuantown.business.post.vo.PostListVO;
import org.springframework.beans.BeanUtils;
import java.util.*;
@Service
@RequiredArgsConstructor
public class InteractionServiceImpl implements InteractionService {
    private final com.yyx.ciyuantown.business.interaction.service.SocialInboxService inbox;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.yyx.ciyuantown.business.chat.ChatService chats;
    private final PostMapper posts;
    private final AppUserMapper users;
    private final JdbcTemplate jdbc;
    private final UserFollowMapper follows;
    private final RedisHotspotService redis;
    private final com.yyx.ciyuantown.business.level.LevelService levels;
    @Override @Transactional
    public InteractionResultVO toggleLike(Long userId, Long postId) { return toggle(userId,postId,"user_like","like_count"); }
    @Override @Transactional
    public InteractionResultVO toggleFavorite(Long userId, Long postId) { return toggle(userId,postId,"user_favorite","favorite_count"); }
    private InteractionResultVO toggle(Long userId, Long postId, String table, String column) {
        var target=posts.selectById(postId);
        levels.lockParticipants(userId,target==null?null:target.getAuthorId());
        var user = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,userId).last("FOR UPDATE"));
        if (user == null || user.hasBeenDeleted() || user.getStatus() != 0) throw new BizException(ResultCode.UNAUTHORIZED);
        // 先锁帖子行，再读取关系；并发 toggle、删帖与计数更新保持一致。
        var post = posts.selectOne(new LambdaQueryWrapper<Post>().eq(Post::getId,postId).last("FOR UPDATE"));
        if (post == null || post.getStatus() != 1) throw new BizException("帖子不存在或已下架");
        // table/column 仅来自上面的内部常量，不接受外部输入。
        var rows = jdbc.queryForList("SELECT id FROM "+table+" WHERE user_id=? AND post_id=? FOR UPDATE",userId,postId);
        boolean active = rows.isEmpty();
        if (active) jdbc.update("INSERT INTO "+table+"(user_id,post_id) VALUES(?,?)",userId,postId);
        else jdbc.update("DELETE FROM "+table+" WHERE user_id=? AND post_id=?",userId,postId);
        jdbc.update("UPDATE post SET "+column+"=GREATEST(0,"+column+"+?) WHERE id=?",active?1:-1,postId);
        int count = jdbc.queryForObject("SELECT "+column+" FROM post WHERE id=?",Integer.class,postId);
        if(active&&"user_like".equals(table))inbox.deliver(post.getAuthorId(),userId,"post_like",postId,null,"post-like:"+postId+":"+userId);
        if(active&&"user_like".equals(table)&&!Objects.equals(userId,post.getAuthorId()))
            levels.award(post.getAuthorId(),"received_like","post:"+postId+":user:"+userId);
        boolean result=active;TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){redis.cacheRelation("ct:"+("user_like".equals(table)?"like":"fav")+":set:"+postId,String.valueOf(userId),result,"ct:"+("user_like".equals(table)?"like":"fav")+":cnt:"+postId,count);}});
        return new InteractionResultVO(active,count);
    }
    @Override @Transactional(readOnly=true)
    public void fillState(PostDetailVO post, Long userId) {
        hydrate(List.of(post), userId);
    }
    @Override @Transactional(readOnly=true)
    public List<PostListVO> withState(List<PostListVO> source, Long userId) {
        Set<String> pending = new HashSet<>();
        for (var item : source) if (Integer.valueOf(0).equals(item.getStatus())) pending.add(item.getId());
        // Public cache objects must never acquire a viewer's relationship state.
        List<PostListVO> copies = source.stream().map(item -> {
            var copy = new PostListVO(); BeanUtils.copyProperties(item, copy); return copy;
        }).toList();
        Set<String> existing = hydrate(copies, userId);
        return copies.stream().filter(p -> existing.contains(p.getId()) && (p.getStatus()==1 || p.getStatus()==0 && pending.contains(p.getId()))).toList();
    }
    private Set<String> hydrate(List<? extends PostListVO> views, Long userId) {
        if (views.isEmpty()) return Set.of();
        var ids = views.stream().map(PostListVO::getId).toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<String, Map<String, Object>> current = new HashMap<>();
        for (var row : jdbc.queryForList("SELECT p.id,p.status,p.like_count,p.favorite_count,p.comment_count,p.share_count,u.level AS current_level,u.deleted AS author_deleted FROM post p LEFT JOIN app_user u ON u.id=p.author_id WHERE p.id IN ("+placeholders+")", ids.toArray()))
            current.put(String.valueOf(row.get("id")), row);
        Set<String> liked = new HashSet<>(), favorited = new HashSet<>(), following = new HashSet<>(), followedBy = new HashSet<>();
        if (userId != null) {
            List<Object> args = new ArrayList<>(); args.add(userId); args.addAll(ids);
            for (var row : jdbc.queryForList("SELECT post_id FROM user_like WHERE user_id=? AND post_id IN ("+placeholders+")", args.toArray())) liked.add(String.valueOf(row.get("post_id")));
            for (var row : jdbc.queryForList("SELECT post_id FROM user_favorite WHERE user_id=? AND post_id IN ("+placeholders+")", args.toArray())) favorited.add(String.valueOf(row.get("post_id")));
            var authors = views.stream().map(PostListVO::getAuthorId).filter(Objects::nonNull).distinct().toList();
            if (!authors.isEmpty()) {
                String authorSlots = String.join(",", Collections.nCopies(authors.size(), "?"));
                args = new ArrayList<>(); args.add(userId); args.addAll(authors);
                for (var row : jdbc.queryForList("SELECT followee_id FROM user_follow WHERE follower_id=? AND followee_id IN ("+authorSlots+")", args.toArray())) following.add(String.valueOf(row.get("followee_id")));
                for (var row : jdbc.queryForList("SELECT follower_id FROM user_follow WHERE followee_id=? AND follower_id IN ("+authorSlots+")", args.toArray())) followedBy.add(String.valueOf(row.get("follower_id")));
            }
        }
        for (var view : views) {
            var row = current.get(view.getId());
            if (row != null) {
                view.setStatus(((Number)row.get("status")).intValue());
                view.setLikeCount(((Number)row.get("like_count")).intValue());
                view.setFavoriteCount(((Number)row.get("favorite_count")).intValue());
                view.setCommentCount(((Number)row.get("comment_count")).intValue());
                view.setShareCount(((Number)row.get("share_count")).intValue());
                if(row.get("current_level") instanceof Number level) {
                    boolean deleted=Boolean.TRUE.equals(row.get("author_deleted")) || row.get("author_deleted") instanceof Number flag && flag.intValue()!=0;
                    view.setAuthorLevel(deleted?0:level.intValue());
                }
            } else {
                view.setStatus(2);
            }
            view.setLiked(liked.contains(view.getId())); view.setFavorited(favorited.contains(view.getId()));
            view.setFollowing(following.contains(view.getAuthorId())); view.setFollowedBy(followedBy.contains(view.getAuthorId()));
        }
        return current.keySet();
    }
    @Override @Transactional
    public InteractionResultVO toggleFollow(Long userId,Long followeeId) {
        if(userId.equals(followeeId))throw new BizException("不能关注自己");
        // Lock both users by ID order: reverse follows must not form a lock cycle.
        var first=users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,Math.min(userId,followeeId)).last("FOR UPDATE"));
        var second=users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,Math.max(userId,followeeId)).last("FOR UPDATE"));
        if(first==null||first.hasBeenDeleted()||first.getStatus()!=0||second==null||second.hasBeenDeleted()||second.getStatus()!=0)
            throw new BizException("用户不存在或已禁用");
        UserFollow row=follows.selectOne(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId,userId).eq(UserFollow::getFolloweeId,followeeId));
        boolean active=row==null;
        if(active)inbox.deliver(followeeId,userId,"follow",null,null,"follow:"+followeeId+":"+userId);
        if(active){row=new UserFollow();row.setFollowerId(userId);row.setFolloweeId(followeeId);follows.insert(row);}else follows.deleteById(row);
        int count=Math.toIntExact(follows.selectCount(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId,userId)));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){
            redis.relation("ct:fl:following:"+userId,"ct:fl:followers:"+followeeId,String.valueOf(followeeId),String.valueOf(userId),active,"ct:fl:cnt:following:"+userId,count);
        }});
        boolean reverse=jdbc.queryForObject("SELECT COUNT(*) FROM user_follow WHERE follower_id=? AND followee_id=?",Long.class,followeeId,userId)>0;
        if(chats!=null)chats.relationship(userId,followeeId);
        return new InteractionResultVO(active,count,reverse);
    }
    @Override @Transactional(readOnly=true)
    public InteractionResultVO followState(Long userId, Long followeeId) {
        var target = users.selectById(followeeId);
        if (target == null || target.hasBeenDeleted() || target.getStatus()!=0) throw new BizException("用户不存在或已禁用");
        boolean active = jdbc.queryForObject("SELECT COUNT(*) FROM user_follow WHERE follower_id=? AND followee_id=?",Long.class,userId,followeeId)>0;
        boolean reverse = jdbc.queryForObject("SELECT COUNT(*) FROM user_follow WHERE follower_id=? AND followee_id=?",Long.class,followeeId,userId)>0;
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM user_follow WHERE follower_id=?",Integer.class,userId);
        return new InteractionResultVO(active,count,reverse);
    }
}
