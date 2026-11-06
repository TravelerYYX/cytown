package com.yyx.ciyuantown.business.publish.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.homeconfig.entity.HomeChannel;
import com.yyx.ciyuantown.business.homeconfig.mapper.HomeChannelMapper;
import com.yyx.ciyuantown.business.interaction.entity.UserFollow;
import com.yyx.ciyuantown.business.interaction.mapper.UserFollowMapper;
import com.yyx.ciyuantown.business.post.entity.Post;
import com.yyx.ciyuantown.business.post.mapper.PostMapper;
import com.yyx.ciyuantown.business.publish.entity.PostTopic;
import com.yyx.ciyuantown.business.publish.entity.Topic;
import com.yyx.ciyuantown.business.publish.mapper.PostTopicMapper;
import com.yyx.ciyuantown.business.publish.mapper.TopicMapper;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PublishFeedService {
    private final PostMapper posts;
    private final PostTopicMapper postTopics;
    private final TopicMapper topics;
    private final HomeChannelMapper channels;
    private final UserFollowMapper follows;
    private final RedisHotspotService redis;

    @Transactional(readOnly = true)
    public int rebuild() {
        redis.evict("ct:feed:ready");
        List<Post> published = posts.selectList(new LambdaQueryWrapper<Post>().eq(Post::getStatus, 1));
        List<PostTopic> links = postTopics.selectList(null);
        Map<Long,List<Long>> topicsByPost = links.stream().collect(Collectors.groupingBy(PostTopic::getPostId,Collectors.mapping(PostTopic::getTopicId,Collectors.toList())));
        Map<Long,List<Long>> followersByAuthor = follows.selectList(null).stream().collect(Collectors.groupingBy(UserFollow::getFolloweeId,Collectors.mapping(UserFollow::getFollowerId,Collectors.toList())));
        List<String> keys = new ArrayList<>();keys.add("ct:feed:global");
        channels.selectList(null).stream().map(HomeChannel::getId).forEach(id->keys.add("ct:feed:channel:"+id));
        topics.selectList(null).stream().map(Topic::getId).forEach(id->keys.add("ct:feed:topic:"+id));
        followersByAuthor.values().stream().flatMap(Collection::stream).distinct().forEach(id->keys.add("ct:feed:following:"+id));
        redis.evict(keys.toArray(String[]::new));
        for(Post post:published){long score=post.getCreateTime()==null?System.currentTimeMillis():post.getCreateTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();redis.feed("ct:feed:global",score,post.getId(),true);if(post.getChannelId()!=null)redis.feed("ct:feed:channel:"+post.getChannelId(),score,post.getId(),true);for(Long topicId:topicsByPost.getOrDefault(post.getId(),List.of()))redis.feed("ct:feed:topic:"+topicId,score,post.getId(),true);for(Long followerId:followersByAuthor.getOrDefault(post.getAuthorId(),List.of()))redis.feed("ct:feed:following:"+followerId,score,post.getId(),true);}
        redis.put("ct:feed:ready","1",java.time.Duration.ofDays(3650));
        return published.size();
    }

    @Scheduled(cron = "${ciyuantown.redis.feed-rebuild-cron:0 20 3 * * *}")
    public void scheduledRebuild(){rebuild();}
}
