package com.yyx.ciyuantown.business.publish.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.*;
import com.yyx.ciyuantown.business.homeconfig.entity.SystemConfig;
import com.yyx.ciyuantown.business.homeconfig.mapper.SystemConfigMapper;
import com.yyx.ciyuantown.business.publish.entity.*;
import com.yyx.ciyuantown.business.publish.mapper.*;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import java.util.*;

@Service @RequiredArgsConstructor
public class PublishAdminService {
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
 private final PublishRuleMapper rules;private final TopicMapper topics;private final PostTopicMapper links;private final EmojiGroupMapper groups;
 private final PublishEmojiMapper emojis;private final TextColorMapper colors;private final DividerStyleMapper dividers;private final SystemConfigMapper configs;
 private final com.yyx.ciyuantown.common.storage.StorageService storage;
 private final ObjectMapper json;private final FfmpegService ffmpeg;private final ObjectProvider<RedisConnectionFactory> redis;
 public record Policy(boolean defaultValue,boolean userEditable){}
 public record Settings(boolean reviewRequired,Policy allowDownload,Policy downloadWatermark,Policy original,JsonNode featureFlags,int pollMinOptions,int pollMaxOptions,int commentImageMax,boolean commentDeleteCascade){}
 public record Health(Map<String,Object> ffmpeg,Map<String,Object> redis){}

 public Settings settings(){JsonNode flags=readJson(value("publish_feature_flags","{}"));JsonNode poll=readJson(value("publish_poll_rule","{\"minOptions\":2,\"maxOptions\":10}"));return new Settings("1".equals(value("publish_review_required","1")),policy("creator_allow_download",true),policy("creator_watermark",false),policy("creator_original",false),flags,poll.path("minOptions").asInt(2),poll.path("maxOptions").asInt(10),integer("comment_image_max",3),"1".equals(value("comment_delete_cascade","0")));}
 @CacheEvict(cacheNames="publish-config",allEntries=true)
 @Transactional public Settings updateSettings(Settings p){if(p==null||p.allowDownload()==null||p.downloadWatermark()==null||p.original()==null)throw new BizException("创作策略不能为空");if(p.pollMinOptions()<2||p.pollMaxOptions()<p.pollMinOptions()||p.pollMaxOptions()>10)throw new BizException("投票选项范围必须为2-10");if(p.commentImageMax()<0||p.commentImageMax()>9)throw new BizException("评论图片上限必须为0-9");upsert("publish_review_required",p.reviewRequired()?"1":"0","新帖子是否先审后发");writePolicy("creator_allow_download",p.allowDownload());writePolicy("creator_watermark",p.downloadWatermark());writePolicy("creator_original",p.original());upsert("publish_feature_flags",write(p.featureFlags()),"帖子编辑器功能开关");upsert("publish_poll_rule","{\"minOptions\":"+p.pollMinOptions()+",\"maxOptions\":"+p.pollMaxOptions()+"}","投票规则");upsert("comment_image_max",String.valueOf(p.commentImageMax()),"每条评论或回复最多图片数，0表示关闭");upsert("comment_delete_cascade",p.commentDeleteCascade()?"1":"0","删除根评论时是否连同回复彻底删除");return settings();}
 public List<PublishRule> rules(){return rules.selectList(new LambdaQueryWrapper<PublishRule>().orderByAsc(PublishRule::getMode));}
 @CacheEvict(cacheNames="publish-config",allEntries=true)
 @Transactional public PublishRule updateRule(PublishRule p){if(p==null||!Set.of("image","video","post").contains(p.getMode()))throw new BizException("发布模式不合法");Integer[] values={p.getTitleMin(),p.getTitleMax(),p.getContentMin(),p.getContentMax(),p.getImageMax(),p.getVideoMax(),p.getTotalMediaMax(),p.getMaxVideoMb()};for(Integer n:values)if(n==null||n<0)throw new BizException("发布限制不能为负数");if(p.getTitleMin()>p.getTitleMax()||p.getContentMin()>p.getContentMax()||p.getImageMax()+p.getVideoMax()<p.getTotalMediaMax()||p.getTotalMediaMax()<Math.max(p.getImageMax(),p.getVideoMax()))throw new BizException("发布限制范围不合法");if("image".equals(p.getMode())&&(p.getImageMax()<1||p.getVideoMax()!=0))throw new BizException("图文模式必须允许图片且不能允许视频");if("video".equals(p.getMode())&&(p.getImageMax()!=0||p.getVideoMax()!=1||p.getTotalMediaMax()!=1))throw new BizException("视频模式必须且只能允许一个视频");if(rules.updateById(p)!=1)throw new BizException("发布规则不存在");return rules.selectById(p.getMode());}
 public List<Topic> topics(){return topics.selectList(new LambdaQueryWrapper<Topic>().orderByAsc(Topic::getSortOrder,Topic::getId));}
 @CacheEvict(cacheNames="publish-config",allEntries=true)
 @Transactional public void sortTopics(List<Long> ids){
  if(ids==null||ids.isEmpty())return;
  for(int i=0;i<ids.size();i++)topics.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Topic>().eq(Topic::getId,ids.get(i)).set(Topic::getSortOrder,i));
 }
 @CacheEvict(cacheNames="publish-config",allEntries=true)
 @Transactional public Topic saveTopic(Topic p){cleanup.track("topic",p.getId());String name=p.getName()==null?"":p.getName().strip();if(name.isEmpty()||name.codePointCount(0,name.length())>50)throw new BizException("话题名称需为1-50个字符");p.setName(name);if(p.getDescription()==null)p.setDescription("");if(p.getCoverUrl()==null)p.setCoverUrl("");if(p.getStatus()==null||p.getStatus()<0||p.getStatus()>2)throw new BizException("话题审核状态不合法");if(p.getEnabled()==null)p.setEnabled(true);if(p.getRecommended()==null)p.setRecommended(false);if(p.getHot()==null)p.setHot(false);if(p.getSortOrder()==null)p.setSortOrder(0);if(p.getViewCount()==null)p.setViewCount(0L);if(p.getId()==null)topics.insert(p);else if(topics.updateById(p)!=1)throw new BizException("话题不存在");return p;}
 @CacheEvict(cacheNames="publish-config",allEntries=true)
 @Transactional public void deleteTopic(Long id){cleanup.track("topic",id);links.delete(new LambdaQueryWrapper<PostTopic>().eq(PostTopic::getTopicId,id));topics.deleteById(id);}
 public List<EmojiGroup> groups(){return groups.selectList(new LambdaQueryWrapper<EmojiGroup>().orderByAsc(EmojiGroup::getSortOrder,EmojiGroup::getId));}
 public List<PublishEmoji> emojis(){return emojis.selectList(new LambdaQueryWrapper<PublishEmoji>().orderByAsc(PublishEmoji::getSortOrder,PublishEmoji::getId));}
 public List<TextColor> colors(){return colors.selectList(new LambdaQueryWrapper<TextColor>().orderByAsc(TextColor::getSortOrder,TextColor::getId));}
 public List<DividerStyle> dividers(){return dividers.selectList(new LambdaQueryWrapper<DividerStyle>().orderByAsc(DividerStyle::getSortOrder,DividerStyle::getId));}
 @CacheEvict(cacheNames="publish-config",allEntries=true)
 @Transactional public EmojiGroup saveGroup(EmojiGroup p){validateName(p.getName());defaults(p);if(p.getId()==null)groups.insert(p);else groups.updateById(p);return p;}
 @CacheEvict(cacheNames="publish-config",allEntries=true)
 @Transactional public PublishEmoji saveEmoji(PublishEmoji p){cleanup.track("publish_emoji",p.getId());validateName(p.getName());if(groups.selectById(p.getGroupId())==null)throw new BizException("表情分组不存在");if(p.getImageUrl()==null||!storage.managed(p.getImageUrl()))throw new BizException("请上传表情图片");defaults(p);if(p.getId()==null)emojis.insert(p);else emojis.updateById(p);return p;}
 @CacheEvict(cacheNames="publish-config",allEntries=true)
 @Transactional public TextColor saveColor(TextColor p){validateName(p.getName());if(p.getColorValue()==null||!p.getColorValue().matches("#[0-9a-fA-F]{6}"))throw new BizException("颜色必须为#RRGGBB");defaults(p);if(p.getId()==null)colors.insert(p);else colors.updateById(p);return p;}
 @CacheEvict(cacheNames="publish-config",allEntries=true)
 @Transactional public DividerStyle saveDivider(DividerStyle p){validateName(p.getName());if(p.getPreviewText()==null||p.getPreviewText().isBlank())throw new BizException("请输入分割线预览");defaults(p);if(p.getId()==null)dividers.insert(p);else dividers.updateById(p);return p;}
 @CacheEvict(cacheNames="publish-config",allEntries=true)
 @Transactional public void deleteCatalog(String type,Long id){if("emoji".equals(type))cleanup.track("publish_emoji",id);switch(type){case"emoji"->emojis.deleteById(id);case"group"->{if(emojis.selectCount(new LambdaQueryWrapper<PublishEmoji>().eq(PublishEmoji::getGroupId,id))>0)throw new BizException("请先删除分组内表情");groups.deleteById(id);}case"color"->colors.deleteById(id);case"divider"->dividers.deleteById(id);default->throw new BizException("资源类型不合法");}}
 public Health health(){Map<String,Object> state=new LinkedHashMap<>();RedisConnectionFactory factory=redis.getIfAvailable();if(factory==null){state.put("available",false);state.put("message","Redis 未启用");}else try(var connection=factory.getConnection()){String pong=connection.ping();state.put("available",true);state.put("message",pong);}catch(Exception e){state.put("available",false);state.put("message","Redis 连接失败");}return new Health(ffmpeg.health(),state);}
 private Policy policy(String key,boolean fallback){String[] p=value(key,(fallback?"1":"0")+",true").split(",",2);return new Policy("1".equals(p[0])||Boolean.parseBoolean(p[0]),p.length<2||Boolean.parseBoolean(p[1]));}
 private void writePolicy(String key,Policy p){if(p==null)throw new BizException("创作策略不能为空");upsert(key,(p.defaultValue()?"1":"0")+","+p.userEditable(),key);}
 private String value(String key,String fallback){SystemConfig row=configs.selectById(key);return row==null?fallback:row.getConfigValue();}
 private int integer(String key,int fallback){try{return Integer.parseInt(value(key,String.valueOf(fallback)));}catch(Exception e){return fallback;}}
 private void upsert(String key,String value,String remark){SystemConfig row=new SystemConfig();row.setConfigKey(key);row.setConfigValue(value);row.setRemark(remark);if(configs.selectById(key)==null)configs.insert(row);else configs.updateById(row);}
 private JsonNode readJson(String value){try{return json.readTree(value);}catch(Exception e){return json.createObjectNode();}}private String write(JsonNode value){try{return json.writeValueAsString(value==null?json.createObjectNode():value);}catch(Exception e){throw new BizException("配置格式不合法");}}
 private static void validateName(String name){if(name==null||name.isBlank()||name.codePointCount(0,name.length())>30)throw new BizException("名称需为1-30个字符");}
 private static void defaults(Object value){try{var type=value.getClass();var enabled=type.getMethod("getEnabled");if(enabled.invoke(value)==null)type.getMethod("setEnabled",Boolean.class).invoke(value,true);var order=type.getMethod("getSortOrder");if(order.invoke(value)==null)type.getMethod("setSortOrder",Integer.class).invoke(value,0);}catch(Exception ignored){}}
}
