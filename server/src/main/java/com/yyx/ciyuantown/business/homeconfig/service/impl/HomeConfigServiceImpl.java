package com.yyx.ciyuantown.business.homeconfig.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.homeconfig.dto.*;
import com.yyx.ciyuantown.business.homeconfig.EntryTarget;
import com.yyx.ciyuantown.business.homeconfig.entity.*;
import com.yyx.ciyuantown.business.homeconfig.mapper.*;
import com.yyx.ciyuantown.business.homeconfig.service.HomeConfigService;
import com.yyx.ciyuantown.business.post.entity.Post;
import com.yyx.ciyuantown.business.post.mapper.PostMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class HomeConfigServiceImpl implements HomeConfigService {
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
 private final HomeChannelMapper channelMapper; private final HomeEntryMapper entryMapper;
 private final SystemConfigMapper configMapper; private final PostMapper postMapper;
 @Override public List<HomeChannel> channels(boolean enabledOnly) {
  var q=new LambdaQueryWrapper<HomeChannel>(); if(enabledOnly) q.eq(HomeChannel::getEnabled,true);
  return channelMapper.selectList(q.orderByAsc(HomeChannel::getSortOrder,HomeChannel::getId));
 }
 @Override @Transactional @CacheEvict(cacheNames={"home-config","publish-config","post-list"},allEntries=true) public HomeChannel saveChannel(HomeChannelParam p,boolean create) {
  validateName(p.getName()); if(!create) requireChannel(p.getId());
  if(Boolean.TRUE.equals(p.getIsDefault())&&!Boolean.TRUE.equals(p.getEnabled())) throw new BizException("默认频道必须启用");
  if(!create){HomeChannel old=requireChannel(p.getId());if(Boolean.TRUE.equals(old.getIsDefault())&&!Boolean.TRUE.equals(p.getIsDefault()))throw new BizException("请先将其他频道设为默认频道");if(Boolean.TRUE.equals(old.getIsDefault())&&!Boolean.TRUE.equals(p.getEnabled()))throw new BizException("默认频道不能停用");}
  var row=new HomeChannel(); BeanUtils.copyProperties(p,row); row.setName(p.getName().trim());
  // Omitted settings from older clients must not reset an existing restriction.
  if(row.getAdminOnly()==null)row.setAdminOnly(create?false:requireChannel(p.getId()).getAdminOnly());
  if(row.getShowAllPosts()==null)row.setShowAllPosts(create?false:requireChannel(p.getId()).getShowAllPosts());
  if(row.getAllowPublish()==null)row.setAllowPublish(create?false:requireChannel(p.getId()).getAllowPublish());
  if(Boolean.TRUE.equals(row.getIsDefault())&&!Boolean.TRUE.equals(row.getAllowPublish()))throw new BizException("默认发布频道必须允许发布");
  if(Boolean.TRUE.equals(row.getIsDefault())) channelMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<HomeChannel>().set(HomeChannel::getIsDefault,false));
  if(create){ row.setId(null);if(channelMapper.selectCount(null)==0&&Boolean.TRUE.equals(row.getAllowPublish()))row.setIsDefault(true); channelMapper.insert(row); } else if(channelMapper.updateById(row)!=1) throw new BizException("频道不存在"); return row;
 }
 @Override @Transactional @CacheEvict(cacheNames={"home-config","publish-config","post-list"},allEntries=true) public void deleteChannel(Long id){ HomeChannel row=requireChannel(id);if(Boolean.TRUE.equals(row.getIsDefault()))throw new BizException("默认频道不能删除，请先更换默认频道");if(postMapper.selectCount(new LambdaQueryWrapper<Post>().eq(Post::getChannelId,id))>0)throw new BizException("频道已被帖子使用，只能停用");channelMapper.deleteById(id); }
 private HomeChannel requireChannel(Long id){ if(id==null) throw new BizException("频道ID不能为空"); var row=channelMapper.selectById(id); if(row==null) throw new BizException("频道不存在"); return row; }
 @Override public List<HomeEntry> entries(boolean enabledOnly) {
  var q=new LambdaQueryWrapper<HomeEntry>(); if(enabledOnly) q.eq(HomeEntry::getEnabled,true);
  return entryMapper.selectList(q.orderByAsc(HomeEntry::getSortOrder,HomeEntry::getId));
 }
 @Override @Transactional @CacheEvict(cacheNames="home-config",allEntries=true) public HomeEntry saveEntry(HomeEntryParam p,boolean create){cleanup.track("home_entry",create?null:p.getId());
  validateName(p.getName()); if(p.getIconUrl()==null||p.getIconUrl().isBlank()) throw new BizException("请上传入口图标");
  String targetType=EntryTarget.type(p.getTargetType()), link=EntryTarget.validate(targetType,p.getLinkUrl());
  if(p.getOpenMode()==null||!Set.of("inapp","browser").contains(p.getOpenMode())) throw new BizException("打开方式不合法");
  if(!create) requireEntry(p.getId()); var row=new HomeEntry(); BeanUtils.copyProperties(p,row); row.setName(p.getName().trim());
  row.setTargetType(targetType); row.setLinkUrl(link);
  if("app".equals(targetType)){row.setOpenMode("inapp");row.setShowTopbar(true);}
  if(create){ row.setId(null); entryMapper.insert(row); } else if(entryMapper.updateById(row)!=1) throw new BizException("快捷入口不存在"); return row;
 }
 @Override @Transactional @CacheEvict(cacheNames="home-config",allEntries=true) public void deleteEntry(Long id){cleanup.track("home_entry",id); requireEntry(id); entryMapper.deleteById(id); }
 @Override @Transactional @CacheEvict(cacheNames="home-config",allEntries=true) public void sortChannels(List<Long> ids){
  if(ids==null||ids.isEmpty())return;
  for(int i=0;i<ids.size();i++)channelMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<HomeChannel>().eq(HomeChannel::getId,ids.get(i)).set(HomeChannel::getSortOrder,i));
 }
 @Override @Transactional @CacheEvict(cacheNames="home-config",allEntries=true) public void sortEntries(List<Long> ids){
  if(ids==null||ids.isEmpty())return;
  for(int i=0;i<ids.size();i++)entryMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<HomeEntry>().eq(HomeEntry::getId,ids.get(i)).set(HomeEntry::getSortOrder,i));
 }
 private HomeEntry requireEntry(Long id){ if(id==null) throw new BizException("入口ID不能为空"); var row=entryMapper.selectById(id); if(row==null) throw new BizException("快捷入口不存在"); return row; }
 @Override public HeaderEntryParam headerEntry(){
  return new HeaderEntryParam(value("home_header_name",""),value("home_header_icon",""),value("home_header_target_type","link"),
   value("home_header_link",""),value("home_header_open_mode","inapp"),"1".equals(value("home_header_topbar","1")),"1".equals(value("home_header_enabled","0")));
 }
 @Override @Transactional @CacheEvict(cacheNames="home-config",allEntries=true) public HeaderEntryParam updateHeaderEntry(HeaderEntryParam p){
  if(p==null) throw new BizException("入口配置不能为空");
  boolean enabled=Boolean.TRUE.equals(p.enabled());
  String name=p.name()==null?"":p.name().trim(),icon=p.iconUrl()==null?"":p.iconUrl().trim();
  String type=EntryTarget.type(p.targetType()),link=p.linkUrl()==null?"":p.linkUrl().trim();
  if(enabled||!name.isBlank()) validateName(name);
  if(enabled&&icon.isBlank()) throw new BizException("请上传入口图标");
  if(enabled||!link.isBlank()) link=EntryTarget.validate(type,link);
  if(p.openMode()==null||!Set.of("inapp","browser").contains(p.openMode())) throw new BizException("打开方式不合法");
  String mode="app".equals(type)?"inapp":p.openMode();
  boolean topbar="app".equals(type)||!Boolean.FALSE.equals(p.showTopbar());
  cleanup.trackConfig("home_header_icon");
  upsert("home_header_name",name,"首页搜索左侧入口名称");upsert("home_header_icon",icon,"首页搜索左侧入口图标");
  upsert("home_header_target_type",type,"首页搜索左侧入口跳转类型");upsert("home_header_link",link,"首页搜索左侧入口地址");
  upsert("home_header_open_mode",mode,"首页搜索左侧入口打开方式");upsert("home_header_topbar",topbar?"1":"0","首页搜索左侧入口顶部栏");
  upsert("home_header_enabled",enabled?"1":"0","首页搜索左侧入口开关");
  return new HeaderEntryParam(name,icon,type,link,mode,topbar,enabled);
 }
 @Override public NewsConfig newsConfig(){ return new NewsConfig("1".equals(value("news_enabled","1")),parseInterval(value("news_interval","3")),value("news_empty_text",DEFAULT_EMPTY_TEXT)); }
 @Override public LayoutConfig layoutConfig(){
  String layout=value("home_default_feed_layout","single");
  return new LayoutConfig("waterfall".equals(layout)?"waterfall":"single");
 }
 @Override @Transactional @CacheEvict(cacheNames="home-config",allEntries=true) public LayoutConfig updateLayout(LayoutConfigParam p){
  if(p==null||p.defaultFeedLayout()==null||!Set.of("single","waterfall").contains(p.defaultFeedLayout()))
   throw new BizException("首页帖子布局仅支持 single 或 waterfall");
  upsert("home_default_feed_layout",p.defaultFeedLayout(),"首页默认帖子布局：single 单列，waterfall 双列瀑布");
  return new LayoutConfig(p.defaultFeedLayout());
 }
 @Override @Transactional @CacheEvict(cacheNames="home-config",allEntries=true) public NewsConfig updateNews(NewsConfigParam p){
  boolean enabled=p.enabled()!=null&&p.enabled(); int interval=Math.max(2,Math.min(10,p.interval()==null?3:p.interval()));
  // Older admin clients omit emptyText; preserve the existing value in that case.
  String emptyText=p.emptyText()==null?newsConfig().emptyText():p.emptyText().strip();
  int length=emptyText.codePointCount(0,emptyText.length());
  if(emptyText.isBlank()||length>100) throw new BizException("无资讯时显示文案需为1-100个字符");
  upsert("news_enabled",enabled?"1":"0","首页资讯栏开关：1显示 0隐藏"); upsert("news_interval",String.valueOf(interval),"首页资讯栏轮播间隔秒数");
  upsert("news_empty_text",emptyText,"无首页资讯时显示的文案");
  return new NewsConfig(enabled,interval,emptyText);
 }
 @Override @Cacheable(cacheNames="home-config",key="'app-channel-feed-v3'") public AppHomeConfig appConfig(){
  NewsConfig config=newsConfig(); List<NewsItem> items=List.of();
  if(config.enabled()) items=postMapper.selectList(new LambdaQueryWrapper<Post>().select(Post::getId,Post::getTitle)
    .eq(Post::getStatus,1).eq(Post::getHomeNewsEnabled,true)
    .orderByDesc(Post::getCreateTime,Post::getId)).stream().map(p->new NewsItem(p.getId(),p.getTitle())).toList();
  var channelItems=channels(true).stream().map(c->new ChannelItem(c.getId(),c.getName(),Boolean.TRUE.equals(c.getShowAllPosts()))).toList();
  var entryItems=entries(true).stream().map(e->new EntryItem(e.getId(),e.getName(),e.getIconUrl(),e.getTargetType(),e.getLinkUrl(),e.getOpenMode(),e.getShowTopbar())).toList();
  var header=headerEntry();
  return new AppHomeConfig(channelItems,entryItems,new News(config.enabled(),config.interval(),config.emptyText(),items),layoutConfig().defaultFeedLayout(),Boolean.TRUE.equals(header.enabled())?header:null);
 }
 private String value(String key,String fallback){ var row=configMapper.selectById(key); return row==null?fallback:row.getConfigValue(); }
 private int parseInterval(String value){ try{return Math.max(2,Math.min(10,Integer.parseInt(value)));}catch(Exception ignored){return 3;} }
 private void upsert(String key,String value,String remark){ var row=new SystemConfig(); row.setConfigKey(key); row.setConfigValue(value); row.setRemark(remark); if(configMapper.selectById(key)==null) configMapper.insert(row); else configMapper.updateById(row); }
 private static void validateName(String name){ if(name==null) throw new BizException("名称不能为空"); String n=name.trim(); int length=n.codePointCount(0,n.length()); if(length<1||length>20) throw new BizException("名称需为1-20个字符"); }
}
