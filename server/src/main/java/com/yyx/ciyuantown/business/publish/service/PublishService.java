package com.yyx.ciyuantown.business.publish.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.*;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.homeconfig.entity.*;
import com.yyx.ciyuantown.business.homeconfig.mapper.*;
import com.yyx.ciyuantown.business.interaction.entity.UserFollow;
import com.yyx.ciyuantown.business.interaction.mapper.UserFollowMapper;
import com.yyx.ciyuantown.business.post.entity.*;
import com.yyx.ciyuantown.business.post.mapper.*;
import com.yyx.ciyuantown.business.post.vo.PostDetailVO;
import com.yyx.ciyuantown.business.publish.dto.PublishRequest;
import com.yyx.ciyuantown.business.publish.entity.*;
import com.yyx.ciyuantown.business.publish.mapper.*;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import org.springframework.cache.annotation.Cacheable;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class PublishService {
 private final com.yyx.ciyuantown.business.interaction.service.SocialInboxService inbox;
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
 private static final Set<String> MODES=Set.of("image","video","post");
 private static final Set<String> BLOCKS=Set.of("paragraph","image","video","divider","poll");
 private static final Set<String> RUNS=Set.of("text","emoji","mention","link");
 private final PublishRuleMapper rules; private final TopicMapper topics; private final PostTopicMapper postTopics;
 private final PostDraftMapper drafts; private final UploadAssetMapper assets; private final EmojiGroupMapper emojiGroups;
 private final PublishEmojiMapper emojis; private final TextColorMapper colors; private final DividerStyleMapper dividers;
 private final PostPollMapper polls; private final PostPollOptionMapper pollOptions; private final PostPollVoteMapper votes;
 private final HomeChannelMapper channels; private final SystemConfigMapper configs; private final AppUserMapper users;
 private final com.yyx.ciyuantown.business.appuser.service.AppUserAccessService userAccess;
 private final UserFollowMapper follows; private final PostMapper posts; private final PostMediaMapper postMedia; private final PostTagMapper postTags;
 private final ObjectMapper json;
 private final RedisHotspotService redis;
 private final PublishChannelAccessService channelAccess;
 private final com.yyx.ciyuantown.business.level.LevelService levels;

 public record CreatorPolicy(boolean defaultValue,boolean userEditable) {}
 public record PublishConfig(List<PublishRule> rules,List<HomeChannel> channels,Long defaultChannelId,
  Map<String,CreatorPolicy> creatorPolicies,boolean reviewRequired,JsonNode featureFlags,JsonNode pollRule,
  List<Topic> topics,List<EmojiGroup> emojiGroups,List<PublishEmoji> emojis,List<TextColor> colors,List<DividerStyle> dividers) {}
 public record DraftView(Long id,Long revision,String mode,String title,String summary,String coverUrl,JsonNode payload,String updateTime) {}
 public record SubmitResult(String id,Integer status,String message) {}
 public record FollowingView(String userId,String publicUid,String nickname,String avatar) {}

 @Transactional(readOnly=true) @Cacheable(cacheNames="publish-config",key="'channel-access-v2:' + (#p0 ? 'official' : 'regular')")
 public PublishConfig config(boolean official) {
  var query=new LambdaQueryWrapper<HomeChannel>().eq(HomeChannel::getEnabled,true).eq(HomeChannel::getAllowPublish,true);
  if(!official)query.eq(HomeChannel::getAdminOnly,false);
  var channelRows=channels.selectList(query.orderByAsc(HomeChannel::getSortOrder,HomeChannel::getId));
  Long defaultId=channelRows.stream().filter(c->Boolean.TRUE.equals(c.getIsDefault())).map(HomeChannel::getId).findFirst().orElse(channelRows.isEmpty()?null:channelRows.getFirst().getId());
  var visibleTopics=topics.selectList(new LambdaQueryWrapper<Topic>().eq(Topic::getStatus,1).eq(Topic::getEnabled,true)
   .orderByDesc(Topic::getRecommended).orderByDesc(Topic::getHot).orderByAsc(Topic::getSortOrder,Topic::getId));
  return new PublishConfig(rules.selectList(new LambdaQueryWrapper<PublishRule>().orderByAsc(PublishRule::getMode)),channelRows,defaultId,
   Map.of("allowDownload",policy("creator_allow_download",true),"downloadWatermark",policy("creator_watermark",false),"original",policy("creator_original",false)),
   boolConfig("publish_review_required",true),jsonConfig("publish_feature_flags","{}"),jsonConfig("publish_poll_rule","{\"minOptions\":2,\"maxOptions\":10}"),visibleTopics,
   emojiGroups.selectList(new LambdaQueryWrapper<EmojiGroup>().eq(EmojiGroup::getEnabled,true).orderByAsc(EmojiGroup::getSortOrder,EmojiGroup::getId)),
   emojis.selectList(new LambdaQueryWrapper<PublishEmoji>().eq(PublishEmoji::getEnabled,true).orderByAsc(PublishEmoji::getSortOrder,PublishEmoji::getId)),
   colors.selectList(new LambdaQueryWrapper<TextColor>().eq(TextColor::getEnabled,true).orderByAsc(TextColor::getSortOrder,TextColor::getId)),
   dividers.selectList(new LambdaQueryWrapper<DividerStyle>().eq(DividerStyle::getEnabled,true).orderByAsc(DividerStyle::getSortOrder,DividerStyle::getId)));
 }

 @Transactional(readOnly=true)
 public List<Topic> searchTopics(String keyword) {
  var q=new LambdaQueryWrapper<Topic>().eq(Topic::getStatus,1).eq(Topic::getEnabled,true);
  if(keyword!=null&&!keyword.isBlank())q.like(Topic::getName,keyword.strip());
  return topics.selectList(q.orderByDesc(Topic::getRecommended).orderByDesc(Topic::getHot).orderByAsc(Topic::getSortOrder).last("LIMIT 50"));
 }

 @Transactional
 public Topic createCustomTopic(Long userId,PublishRequest.CustomTopic p) {
  userAccess.requireForUpdate(userId);
  if(!redis.allow("topic",String.valueOf(userId),10,Duration.ofHours(1)))throw new BizException("创建话题过于频繁，请稍后再试");
  String name=trim(p.name()); int n=length(name); if(n<1||n>50)throw new BizException("话题名称需为1-50个字符");
  var existing=topics.selectOne(new LambdaQueryWrapper<Topic>().eq(Topic::getName,name)); if(existing!=null)return existing;
  var row=new Topic(); row.setName(name);row.setDescription(limit(trim(p.description()),255));row.setCoverUrl("");row.setStatus(0);
  row.setRecommended(false);row.setHot(false);row.setEnabled(true);row.setSortOrder(0);row.setCreatorId(userId);row.setViewCount(0L);topics.insert(row);return row;
 }

 @Transactional(readOnly=true)
 public List<FollowingView> following(Long userId,String keyword) {
  var rows=follows.selectList(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId,userId).orderByDesc(UserFollow::getCreateTime).last("LIMIT 100"));
  if(rows.isEmpty())return List.of();
  var found=users.selectBatchIds(rows.stream().map(UserFollow::getFolloweeId).toList());
  return found.stream().filter(u->!u.hasBeenDeleted()&&u.getStatus()==0).filter(u->keyword==null||keyword.isBlank()||u.getNickname().contains(keyword.strip())||String.valueOf(u.getPublicUid()).contains(keyword.strip()))
   .map(u->new FollowingView(u.getId().toString(),String.valueOf(u.getPublicUid()),u.getNickname(),u.getAvatar())).toList();
 }

 @Transactional
 public DraftView saveDraft(Long userId,PublishRequest.DraftSave p) {
  cleanup.track("post_draft",p==null?null:p.id());
  userAccess.requireForUpdate(userId);
  if(!redis.allow("draft",String.valueOf(userId),120,Duration.ofMinutes(1)))throw new BizException("草稿保存过于频繁，请稍后再试");
  if(p==null)throw new BizException("草稿内容不能为空");
  requireMode(p.mode()); if(p.payload()==null||!p.payload().isObject())throw new BizException("草稿内容格式不合法");
  String payload;try{payload=json.writeValueAsString(p.payload());}catch(Exception e){throw new BizException("草稿内容格式不合法");}
  String title=limit(trim(p.title()),100),summary=limit(trim(p.summary()),255),cover=orEmpty(p.coverUrl());
  if(p.id()==null){PostDraft row=new PostDraft();row.setAuthorId(userId);row.setRevision(1L);row.setMode(p.mode());row.setTitle(title);row.setSummary(summary);row.setCoverUrl(cover);row.setPayload(payload);drafts.insert(row);return draftView(row);}
  if(p.revision()==null)throw new BizException("草稿修订号不能为空");
  int changed=drafts.update(null,new LambdaUpdateWrapper<PostDraft>().eq(PostDraft::getId,p.id()).eq(PostDraft::getAuthorId,userId).eq(PostDraft::getRevision,p.revision()).set(PostDraft::getMode,p.mode()).set(PostDraft::getTitle,title).set(PostDraft::getSummary,summary).set(PostDraft::getCoverUrl,cover).set(PostDraft::getPayload,payload).setSql("revision=revision+1"));
  if(changed!=1){PostDraft current=drafts.selectById(p.id());if(current==null||!Objects.equals(current.getAuthorId(),userId))throw new BizException("草稿不存在");throw new BizException("草稿已在其他设备更新，请重新打开");}
  return draftView(drafts.selectById(p.id()));
 }
 @Transactional(readOnly=true) public List<DraftView> drafts(Long userId){return drafts.selectList(new LambdaQueryWrapper<PostDraft>().eq(PostDraft::getAuthorId,userId).orderByDesc(PostDraft::getUpdateTime,PostDraft::getId)).stream().map(this::draftView).toList();}
 @Transactional(readOnly=true) public DraftView draft(Long userId,Long id){return draftView(requireDraft(userId,id));}
 @Transactional public void deleteDraft(Long userId,Long id){cleanup.track("post_draft",id);userAccess.requireForUpdate(userId);drafts.deleteById(requireDraft(userId,id));}

 @Transactional
 public SubmitResult submit(Long userId,PublishRequest.Submit p) {
  cleanup.track("post_draft",p==null?null:p.draftId());
  AppUser author=userAccess.requireForUpdate(userId);
  if(p==null)throw new BizException("发布内容不能为空");
  // The resource gate acquired above serializes concurrent submissions before this read.
  // Count persisted submissions so validation failures and rollbacks consume nothing.
  if(posts.selectCount(new LambdaQueryWrapper<Post>().eq(Post::getAuthorId,userId)
   .ge(Post::getCreateTime,LocalDateTime.now().minusMinutes(10)))>=10)
   throw new BizException("发布过于频繁，10分钟内最多发布10条，请稍后再试");
  requireMode(p.mode());
  if(p.repostOfPostId()!=null){Post source=posts.selectById(p.repostOfPostId());if(source==null||source.getStatus()!=1)throw new BizException("原帖不存在或已下架");if(!"post".equals(p.mode()))throw new BizException("转发请使用帖子模式");}
  PublishRule rule=Optional.ofNullable(rules.selectById(p.mode())).orElseThrow(()->new BizException("发布规则未配置"));
  String title=trim(p.title());String content="post".equals(p.mode())?"":trim(p.content());
  checkLength("标题",title,p.repostOfPostId()==null?rule.getTitleMin():0,rule.getTitleMax());
  HomeChannel channel=channels.selectById(p.channelId());if(channel==null||!Boolean.TRUE.equals(channel.getEnabled()))throw new BizException("请选择有效频道");
  if(!Boolean.TRUE.equals(channel.getAllowPublish()))throw new BizException("该频道不允许发布");
  if(Boolean.TRUE.equals(channel.getAdminOnly())&&!channelAccess.isOfficial(author))throw new BizException("该频道仅管理员可发布");
  List<Long> ids=distinct(p.assetIds()); List<UploadAsset> mediaRows=ids.isEmpty()?List.of():assets.selectBatchIds(ids);
  if(mediaRows.size()!=ids.size()||mediaRows.stream().anyMatch(a->!Objects.equals(a.getOwnerId(),userId)||(!"ready".equals(a.getStatus())&&!("video".equals(a.getMediaType())&&"processing".equals(a.getStatus())))||Boolean.TRUE.equals(a.getAttached())))throw new BizException("媒体不存在、处理失败或不属于当前用户");
  long imageCount=mediaRows.stream().filter(a->"image".equals(a.getMediaType())).count(),videoCount=mediaRows.size()-imageCount;
  validateMedia(p.mode(),rule,imageCount,videoCount,mediaRows);
  if("post".equals(p.mode())){
   Map<Long,String> assetTypes=mediaRows.stream().collect(Collectors.toMap(UploadAsset::getId,UploadAsset::getMediaType));
   validateBlocks(userId,p.blocks(),assetTypes);
   boolean hasPollBlock=blocks(p.blocks()).stream().anyMatch(b->"poll".equals(b.path("type").asText()));
   if(hasPollBlock!=(p.poll()!=null))throw new BizException("投票内容与投票块不一致");
   content=plainText(p.blocks());
  }
  checkLength("正文",content,p.repostOfPostId()==null?rule.getContentMin():0,rule.getContentMax());
  if("post".equals(p.mode())&&p.repostOfPostId()==null&&content.isEmpty()&&mediaRows.isEmpty()){
   throw new BizException("帖子至少需要正文或一个媒体块");
  }
  LinkedHashMap<Long,Topic> selected=resolveTopics(userId,p.topicIds(),p.customTopics());
  if(selected.size()>5)throw new BizException("每次发布不能超过5个话题");
  CreatorPolicy allow=policy("creator_allow_download",true),watermark=policy("creator_watermark",false),original=policy("creator_original",false);
  boolean allowDownload=resolve(allow,p.allowDownload()),downloadWatermark=allowDownload&&resolve(watermark,p.downloadWatermark());
  Post post=new Post();post.setAuthorId(userId);post.setAuthorNickname(author.getNickname());post.setAuthorLevel(author.getLevel());post.setAuthorBio("");
  post.setMode(p.mode());post.setChannelId(channel.getId());post.setTitle(title);post.setContent(content);post.setContentBlocks("post".equals(p.mode())?write(p.blocks()):null);
  post.setStatus(boolConfig("publish_review_required",true)?0:1);post.setHomeNewsEnabled(false);post.setCollection("");post.setPermission("");
  post.setAllowDownload(allowDownload);post.setDownloadWatermark(downloadWatermark);post.setOriginal(resolve(original,p.original()));
  post.setRepostOfPostId(p.repostOfPostId());
  post.setLikeCount(0);post.setFavoriteCount(0);post.setCommentCount(0);post.setShareCount(0);posts.insert(post);
  int order=0;for(UploadAsset asset:mediaRows){var m=new PostMedia();m.setPostId(post.getId());m.setAssetId(asset.getId());m.setType(asset.getMediaType());m.setWidth(asset.getWidth());m.setHeight(asset.getHeight());
   m.setAspectRatio(asset.getWidth()==null||asset.getHeight()==null||asset.getHeight()==0?1d:(double)asset.getWidth()/asset.getHeight());m.setLayout(layout(m.getAspectRatio()));m.setDurationMs(asset.getDurationMs());m.setDuration(formatDuration(asset.getDurationMs()));
   m.setUrl(asset.getUrl());m.setCoverUrl(orEmpty(asset.getCoverUrl()));m.setCoverTimeMs(asset.getCoverTimeMs());m.setCropJson(orEmpty(asset.getCropJson()));m.setSortOrder(order++);postMedia.insert(m);
   assets.update(null,new LambdaUpdateWrapper<UploadAsset>().eq(UploadAsset::getId,asset.getId()).set(UploadAsset::getAttached,true));}
  order=0;for(Topic topic:selected.values()){var link=new PostTopic();link.setPostId(post.getId());link.setTopicId(topic.getId());link.setSortOrder(order);postTopics.insert(link);var legacy=new PostTag();legacy.setPostId(post.getId());legacy.setName(topic.getName());legacy.setSortOrder(order++);postTags.insert(legacy);}
  createPoll(post.getId(),p.poll());if(p.draftId()!=null){PostDraft draft=requireDraft(userId,p.draftId());drafts.deleteById(draft);}
  if(post.getStatus()==1){long id=post.getId(),score=System.currentTimeMillis();Long channelId=post.getChannelId();var followerIds=follows.selectList(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFolloweeId,userId)).stream().map(UserFollow::getFollowerId).toList();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){redis.feed("ct:feed:global",score,id,true);redis.feed("ct:feed:channel:"+channelId,score,id,true);for(Topic topic:selected.values())redis.feed("ct:feed:topic:"+topic.getId(),score,id,true);for(Long followerId:followerIds)redis.feed("ct:feed:following:"+followerId,score,id,true);}});}
  if(post.getStatus()==1)levels.award(userId,"post","post:"+post.getId());
  inbox.published(post);
  return new SubmitResult(post.getId().toString(),post.getStatus(),post.getStatus()==0?"已提交审核":"发布成功");
 }

 @Transactional
 public PostDetailVO.PollView vote(Long userId,Long postId,Long optionId){userAccess.requireForUpdate(userId);if(!redis.allow("vote",String.valueOf(userId),30,Duration.ofMinutes(1)))throw new BizException("投票过于频繁");Post post=posts.selectById(postId);if(post==null||post.getStatus()!=1)throw new BizException("帖子不存在或未发布");PostPoll poll=polls.selectOne(new LambdaQueryWrapper<PostPoll>().eq(PostPoll::getPostId,postId).last("FOR UPDATE"));if(poll==null)throw new BizException("投票不存在");if(poll.getEndsAt()!=null&&!poll.getEndsAt().isAfter(LocalDateTime.now()))throw new BizException("投票已截止");PostPollOption option=pollOptions.selectById(optionId);if(option==null||!Objects.equals(option.getPollId(),poll.getId()))throw new BizException("投票选项不存在");if(votes.selectCount(new LambdaQueryWrapper<PostPollVote>().eq(PostPollVote::getPollId,poll.getId()).eq(PostPollVote::getUserId,userId))>0)throw new BizException("你已经投过票");var vote=new PostPollVote();vote.setPollId(poll.getId());vote.setOptionId(optionId);vote.setUserId(userId);votes.insert(vote);polls.update(null,new LambdaUpdateWrapper<PostPoll>().eq(PostPoll::getId,poll.getId()).setSql("total_votes=total_votes+1"));pollOptions.update(null,new LambdaUpdateWrapper<PostPollOption>().eq(PostPollOption::getId,optionId).setSql("vote_count=vote_count+1"));PostDetailVO.PollView result=pollView(postId,userId);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){redis.cacheRelation("ct:poll:voters:"+poll.getId(),String.valueOf(userId),true,"ct:poll:count:"+poll.getId(),result.totalVotes());for(var item:result.options())redis.put("ct:poll:option-count:"+item.id(),String.valueOf(item.voteCount()),Duration.ofHours(6));}});return result;}

 @Transactional(readOnly=true)
 public PostDetailVO.PollView pollView(Long postId,Long userId){PostPoll poll=polls.selectOne(new LambdaQueryWrapper<PostPoll>().eq(PostPoll::getPostId,postId));if(poll==null)return null;Long selected=null;if(userId!=null){PostPollVote vote=votes.selectOne(new LambdaQueryWrapper<PostPollVote>().eq(PostPollVote::getPollId,poll.getId()).eq(PostPollVote::getUserId,userId));if(vote!=null)selected=vote.getOptionId();}var options=pollOptions.selectList(new LambdaQueryWrapper<PostPollOption>().eq(PostPollOption::getPollId,poll.getId()).orderByAsc(PostPollOption::getSortOrder,PostPollOption::getId)).stream().map(o->new PostDetailVO.PollOptionView(o.getId(),o.getOptionText(),o.getVoteCount())).toList();return new PostDetailVO.PollView(poll.getId(),poll.getQuestion(),poll.getEndsAt()==null?null:poll.getEndsAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),poll.getTotalVotes(),selected,options);}

 private void validateBlocks(Long userId,JsonNode root,Map<Long,String> assetTypes){
  if(root==null||!root.isArray()||root.size()>500)throw new BizException("帖子正文格式不合法");
  Set<Long> referenced=new HashSet<>();int pollCount=0,totalRuns=0;
  for(JsonNode block:root){String type=block.path("type").asText();if(!BLOCKS.contains(type))throw new BizException("帖子包含不支持的内容块");
   if("image".equals(type)||"video".equals(type)){long assetId=block.path("assetId").asLong();if(!type.equals(assetTypes.get(assetId))||!referenced.add(assetId))throw new BizException("正文媒体引用无效或重复");}
   else if("divider".equals(type)){requireFeature("divider");var row=dividers.selectById(block.path("styleId").asLong());if(row==null||!Boolean.TRUE.equals(row.getEnabled()))throw new BizException("分割线样式已停用");if(block instanceof com.fasterxml.jackson.databind.node.ObjectNode o)o.put("previewText",row.getPreviewText());}
   else if("poll".equals(type)){requireFeature("poll");if(++pollCount>1)throw new BizException("每篇帖子最多一个投票");}
   else if("paragraph".equals(type)){JsonNode runs=block.path("runs");if(!runs.isArray()||(totalRuns+=runs.size())>2000)throw new BizException("段落格式不合法");for(JsonNode run:runs)validateRun(userId,run);}
  }
  if(!referenced.equals(assetTypes.keySet()))throw new BizException("帖子媒体必须全部加入正文且只能引用一次");
 }
 private void validateRun(Long userId,JsonNode run){String type=run.path("type").asText();if(!RUNS.contains(type))throw new BizException("段落包含不支持的节点");
  if("text".equals(type)&&run.hasNonNull("colorId")){requireFeature("color");var color=colors.selectById(run.path("colorId").asLong());if(color==null||!Boolean.TRUE.equals(color.getEnabled()))throw new BizException("文字颜色已停用");if(run instanceof com.fasterxml.jackson.databind.node.ObjectNode o)o.put("colorValue",color.getColorValue());}
  if("emoji".equals(type)){requireFeature("emoji");var emoji=emojis.selectById(run.path("emojiId").asLong());if(emoji==null||!Boolean.TRUE.equals(emoji.getEnabled()))throw new BizException("表情已停用");if(run instanceof com.fasterxml.jackson.databind.node.ObjectNode o){o.put("name",emoji.getName());o.put("imageUrl",emoji.getImageUrl());}}
  if("mention".equals(type)){requireFeature("mention");long id=run.path("userId").asLong();if(follows.selectCount(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId,userId).eq(UserFollow::getFolloweeId,id))==0)throw new BizException("只能@已关注的用户");AppUser mentioned=users.selectById(id);if(mentioned==null||mentioned.hasBeenDeleted()||mentioned.getStatus()!=0)throw new BizException("@用户不存在");if(run instanceof com.fasterxml.jackson.databind.node.ObjectNode o){o.put("nickname",mentioned.getNickname());o.put("publicUid",mentioned.getPublicUid()==null?"":String.valueOf(mentioned.getPublicUid()));}}
  if("link".equals(type)){requireFeature("link");String text=trim(run.path("text").asText()),url=trim(run.path("url").asText()),mode=run.path("openMode").asText();if(text.isEmpty()||(!url.matches("(?i)^https?://.+"))||!Set.of("inapp","browser").contains(mode))throw new BizException("链接格式不合法");}
 }
 private LinkedHashMap<Long,Topic> resolveTopics(Long userId,List<Long> topicIds,List<String> names){var result=new LinkedHashMap<Long,Topic>();for(Long id:distinct(topicIds)){Topic t=topics.selectById(id);if(t==null||t.getStatus()==2||!Boolean.TRUE.equals(t.getEnabled()))throw new BizException("话题不存在或已停用");result.put(t.getId(),t);}for(String name:Optional.ofNullable(names).orElse(List.of())){Topic t=createCustomTopic(userId,new PublishRequest.CustomTopic(name,""));result.put(t.getId(),t);}return result;}
 private void createPoll(Long postId,PublishRequest.Poll p){if(p==null)return;requireFeature("poll");String question=trim(p.question());if(length(question)>200)throw new BizException("投票问题不能超过200字");List<String> options=Optional.ofNullable(p.options()).orElse(List.of()).stream().map(PublishService::trim).filter(s->!s.isEmpty()).distinct().toList();JsonNode rule=jsonConfig("publish_poll_rule","{\"minOptions\":2,\"maxOptions\":10}");int min=rule.path("minOptions").asInt(2),max=rule.path("maxOptions").asInt(10);if(options.size()<min||options.size()>max)throw new BizException("投票选项需为"+min+"-"+max+"个");if(p.endsAt()!=null&&!p.endsAt().isAfter(LocalDateTime.now()))throw new BizException("投票截止时间必须晚于当前时间");var poll=new PostPoll();poll.setPostId(postId);poll.setQuestion(question);poll.setEndsAt(p.endsAt());poll.setTotalVotes(0);polls.insert(poll);for(int i=0;i<options.size();i++){var o=new PostPollOption();o.setPollId(poll.getId());o.setOptionText(limit(options.get(i),100));o.setVoteCount(0);o.setSortOrder(i);pollOptions.insert(o);}}
 private PostDraft requireDraft(Long userId,Long id){if(id==null)throw new BizException("草稿ID不能为空");var row=drafts.selectById(id);if(row==null||!Objects.equals(row.getAuthorId(),userId))throw new BizException("草稿不存在");return row;}
 private DraftView draftView(PostDraft row){try{return new DraftView(row.getId(),row.getRevision(),row.getMode(),row.getTitle(),row.getSummary(),row.getCoverUrl(),json.readTree(row.getPayload()),row.getUpdateTime()==null?"":row.getUpdateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));}catch(Exception e){throw new BizException("草稿内容损坏");}}
 private CreatorPolicy policy(String key,boolean fallback){String value=value(key,(fallback?"1":"0")+",true");String[] p=value.split(",",2);return new CreatorPolicy("1".equals(p[0])||"true".equalsIgnoreCase(p[0]),p.length<2||Boolean.parseBoolean(p[1]));}
 private boolean resolve(CreatorPolicy p,Boolean requested){return p.userEditable()&&requested!=null?requested:p.defaultValue();}
 private boolean boolConfig(String key,boolean fallback){return "1".equals(value(key,fallback?"1":"0"));}
 private JsonNode jsonConfig(String key,String fallback){try{return json.readTree(value(key,fallback));}catch(Exception e){try{return json.readTree(fallback);}catch(Exception impossible){return json.createObjectNode();}}}
 private String value(String key,String fallback){SystemConfig row=configs.selectById(key);return row==null?fallback:row.getConfigValue();}
 private void validateMedia(String mode,PublishRule r,long images,long videos,List<UploadAsset> media){int total=media.size();if(images>r.getImageMax()||videos>r.getVideoMax()||total>r.getTotalMediaMax())throw new BizException("媒体数量超过后台限制");if(r.getMaxVideoMb()!=null&&r.getMaxVideoMb()>0&&media.stream().filter(a->"video".equals(a.getMediaType())).anyMatch(a->a.getSizeBytes()>r.getMaxVideoMb()*1024L*1024L))throw new BizException("视频大小不能超过"+r.getMaxVideoMb()+"MB");if("image".equals(mode)&&(images<1||videos>0))throw new BizException("图文至少需要一张图片且不能包含视频");if("video".equals(mode)&&(videos!=1||images>0))throw new BizException("视频发布必须且只能包含一个视频");}
 private void requireFeature(String name){if(!jsonConfig("publish_feature_flags","{}").path(name).asBoolean(true))throw new BizException("后台已关闭该发布功能");}
 private static List<JsonNode> blocks(JsonNode root){if(root==null||!root.isArray())return List.of();List<JsonNode> result=new ArrayList<>();root.forEach(result::add);return result;}
 private String plainText(JsonNode blocks){if(blocks==null||!blocks.isArray())return "";StringBuilder out=new StringBuilder();for(JsonNode b:blocks){if("paragraph".equals(b.path("type").asText()))for(JsonNode r:b.path("runs")){String t=r.path("type").asText();if("text".equals(t)||"link".equals(t))out.append(r.path("text").asText());else if("mention".equals(t))out.append('@').append(r.path("nickname").asText());else if("emoji".equals(t))out.append('[').append(r.path("name").asText("表情")).append(']');}out.append('\n');}return out.toString().strip();}
 private static void checkLength(String name,String value,Integer min,Integer max){int n=length(value),low=min==null?0:min,high=max==null?Integer.MAX_VALUE:max;if(n<low||n>high)throw new BizException(name+"需为"+low+"-"+high+"个字符");}
 private static List<Long> distinct(List<Long> ids){return Optional.ofNullable(ids).orElse(List.of()).stream().filter(Objects::nonNull).distinct().toList();}
 private static void requireMode(String mode){if(!MODES.contains(mode))throw new BizException("发布模式不合法");}
 private static String writeNode(ObjectMapper json,JsonNode node){try{return json.writeValueAsString(node);}catch(Exception e){throw new BizException("正文格式不合法");}}
 private String write(JsonNode node){return writeNode(json,node==null?json.createArrayNode():node);}
 private static String layout(double ratio){return ratio>1.2?"wide":ratio<.85?"portrait":"square";}
 private static String formatDuration(Long ms){if(ms==null)return null;long sec=ms/1000;return String.format("%02d:%02d",sec/60,sec%60);}
 private static String trim(String value){return value==null?"":value.strip();}private static String orEmpty(String value){return value==null?"":value;}
 private static int length(String s){return s.codePointCount(0,s.length());}private static String limit(String s,int max){return length(s)<=max?s:s.substring(0,s.offsetByCodePoints(0,max));}
}
