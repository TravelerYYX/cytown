package com.yyx.ciyuantown.business.notice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.*;
import com.yyx.ciyuantown.business.notice.dto.*;
import com.yyx.ciyuantown.business.notice.entity.*;
import com.yyx.ciyuantown.business.notice.mapper.*;
import com.yyx.ciyuantown.business.notice.service.NoticeService;
import com.yyx.ciyuantown.business.notice.vo.*;
import com.yyx.ciyuantown.business.post.mapper.PostMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class NoticeServiceImpl implements NoticeService {
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
 private final com.yyx.ciyuantown.common.storage.StorageService storage;
 private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
 private final com.yyx.ciyuantown.business.notice.service.NoticeDeliveryService delivery; private final com.yyx.ciyuantown.business.notice.service.NoticePushService push;
 private final NoticeMapper noticeMapper; private final NoticeCategoryMapper categoryMapper; private final PostMapper postMapper; private final ObjectMapper json;
 @Override public List<NoticeCategory> categories(boolean enabledOnly){ var q=new LambdaQueryWrapper<NoticeCategory>(); if(enabledOnly)q.eq(NoticeCategory::getEnabled,true); var rows=categoryMapper.selectList(q.orderByAsc(NoticeCategory::getSortOrder,NoticeCategory::getId));rows.forEach(c->c.setRecipientUserIds(delivery.categoryRecipients(c.getId())));return rows; }
 @Override @Transactional @CacheEvict(cacheNames="notice",allEntries=true) public NoticeCategory saveCategory(NoticeCategoryParam p,boolean create){cleanup.track("notice_category",create?null:p.getId());
  delivery.validatePolicy(p); validateName(p.getName()); if(!create)requireCategory(p.getId()); var row=new NoticeCategory(); BeanUtils.copyProperties(p,row); row.setName(p.getName().trim()); if(row.getIconUrl()==null)row.setIconUrl("");
  if(create){row.setId(null);categoryMapper.insert(row);}else if(categoryMapper.updateById(row)!=1)throw new BizException("通知分类不存在"); delivery.saveCategoryRecipients(row.getId(),p); return row;
 }
 @Override @Transactional @CacheEvict(cacheNames="notice",allEntries=true) public void deleteCategory(Long id){cleanup.track("notice_category",id); requireCategory(id); if(noticeMapper.selectCount(new LambdaQueryWrapper<Notice>().eq(Notice::getCategoryId,id))>0)throw new BizException("请先删除该分类下的通知"); delivery.deleteCategory(id);categoryMapper.deleteById(id); }
 @Override @Transactional(readOnly=true)  public Page<NoticeListVO> list(NoticeQueryParam q,boolean app){
  q.validate(); var filter=new LambdaQueryWrapper<Notice>(); if(q.getCategoryId()!=null)filter.eq(Notice::getCategoryId,q.getCategoryId());
  if(app)filter.eq(Notice::getStatus,1); else if(q.getStatus()!=null)filter.eq(Notice::getStatus,q.getStatus());
  long total=noticeMapper.selectCount(filter); var page=new Page<NoticeListVO>(q.getPageNum(),q.getPageSize(),total); if(q.offset()>=total)return page.setRecords(List.of());
  if(app)filter.orderByDesc(Notice::getPublishTime,Notice::getId);else filter.orderByDesc(Notice::getCreateTime,Notice::getId);
  filter.last("LIMIT "+q.offset()+","+q.getPageSize()); var rows=noticeMapper.selectList(filter);
  Set<Long> ids=rows.stream().map(Notice::getCategoryId).collect(Collectors.toSet()); Map<Long,String> names=ids.isEmpty()?Map.of():categoryMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(NoticeCategory::getId,NoticeCategory::getName));
  return page.setRecords(rows.stream().map(n->toList(n,names.get(n.getCategoryId()))).toList());
 }
 @Override @Transactional(readOnly=true)  public NoticeDetailVO detail(Long id,boolean app){
  Notice row=requireNotice(id); if(app&&row.getStatus()!=1)throw new BizException("通知不存在或已下架"); NoticeDetailVO vo=new NoticeDetailVO();
  vo.setPublicationId(row.getPublicationId());vo.setAudienceType(row.getAudienceType());vo.setPushEnabled(row.getPushEnabled());vo.setRecipientUserIds(delivery.recipients(id,row.getStatus()==1?row.getPublicationId():null));vo.setPushTasks(push.tasks(id));
  vo.setId(row.getId());vo.setCategoryId(row.getCategoryId());vo.setTitle(row.getTitle());vo.setPostId(row.getPostId());vo.setStatus(row.getStatus());vo.setPublishTime(format(row.getPublishTime()));
  NoticeCategory category=categoryMapper.selectById(row.getCategoryId());vo.setCategoryName(category==null?"":category.getName());
  try{vo.setBlocks(app?json.readTree(row.getBlocks()):row.getBlocks());vo.setBlocksJson(row.getBlocks());}catch(Exception e){throw new BizException("正文格式不合法");} return vo;
 }
 @Override @Transactional @CacheEvict(cacheNames="notice",allEntries=true) public Long save(NoticeParam p,boolean create){cleanup.track("notice",create?null:p.getId());
  validate(p);if(!create)delivery.lock(p.getId());Notice old=create?null:requireNotice(p.getId());
  // Ordinary edits cannot silently move a published notice to another audience/category.
  if(old!=null&&old.getStatus()==1&&(!Objects.equals(old.getCategoryId(),p.getCategoryId())||p.getStatus()!=1))throw new BizException("请先下架后再更改分类或转为草稿");
  var category=requireCategory(p.getCategoryId());boolean publishedEdit=old!=null&&old.getStatus()==1;
  var audience=publishedEdit?null:delivery.resolve(category,p);
  if(!publishedEdit&&p.getStatus()==1&&Boolean.TRUE.equals(p.getPushEnabled()))push.requireEnabled();
  Long id;
  if(create){var row=new Notice();BeanUtils.copyProperties(p,row);row.setId(null);row.setStatus(0);row.setAudienceType(audience.type());row.setPushEnabled(Boolean.TRUE.equals(p.getPushEnabled()));row.setTitle(p.getTitle().trim());noticeMapper.insert(row);id=row.getId();}
  else {id=p.getId();noticeMapper.update(null,new LambdaUpdateWrapper<Notice>().eq(Notice::getId,id).set(Notice::getTitle,p.getTitle().trim()).set(Notice::getBlocks,p.getBlocks()).set(Notice::getPostId,p.getPostId()).set(Notice::getCategoryId,p.getCategoryId()));}
  if(!publishedEdit){delivery.saveDraft(id,audience,Boolean.TRUE.equals(p.getPushEnabled()));if(p.getStatus()==1)delivery.publish(id,category,p);}
  return id;
 }
 @Override @Transactional @CacheEvict(cacheNames="notice",allEntries=true) public void delete(Long id){cleanup.track("notice",id);delivery.lock(id);delivery.delete(id);noticeMapper.deleteById(id);}
 @Override @Transactional @CacheEvict(cacheNames="notice",allEntries=true) public void publish(Long id){
  delivery.lock(id);Notice n=requireNotice(id);if(n.getStatus()==1)return;
  var p=new NoticeParam();p.setId(id);p.setCategoryId(n.getCategoryId());p.setTitle(n.getTitle());p.setBlocks(n.getBlocks());p.setPostId(n.getPostId());p.setStatus(1);p.setAudienceType(n.getAudienceType());p.setRecipientUserIds(delivery.recipients(id,null));p.setPushEnabled(n.getPushEnabled());validate(p);delivery.publish(id,requireCategory(n.getCategoryId()),p);
 }
 @Override @Transactional @CacheEvict(cacheNames="notice",allEntries=true) public void unpublish(Long id){delivery.lock(id);delivery.cancel(id);noticeMapper.update(null,new LambdaUpdateWrapper<Notice>().eq(Notice::getId,id).set(Notice::getStatus,0));}
 private void validate(NoticeParam p){
  requireCategory(p.getCategoryId()); if(p.getTitle()==null)throw new BizException("标题不能为空");String title=p.getTitle().trim();int length=title.codePointCount(0,title.length());if(length<1||length>100)throw new BizException("标题需为1-100个字符");
  if(p.getStatus()==null||(p.getStatus()!=0&&p.getStatus()!=1))throw new BizException("状态仅允许0、1"); if(p.getPostId()!=null){var post=postMapper.selectById(p.getPostId());if(post==null||post.getStatus()!=1)throw new BizException("关联帖子不存在或未发布");}
  try{JsonNode root=json.readTree(p.getBlocks());if(root==null||!root.isArray()||root.size()>50)throw new Exception();for(JsonNode block:root){String type=block.path("type").asText();if("text".equals(type)){String text=block.path("text").asText("");if(text.isBlank()||text.codePointCount(0,text.length())>2000)throw new Exception();}else if("image".equals(type)){if(!storage.managed(block.path("url").asText("")))throw new Exception();}else throw new Exception();}}catch(Exception e){throw new BizException("正文格式不合法");}
 }
 private NoticeListVO toList(Notice n,String category){var vo=new NoticeListVO();vo.setAudienceType(n.getAudienceType());vo.setPushEnabled(n.getPushEnabled());vo.setPublicationId(n.getPublicationId());vo.setPushTasks(push.tasks(n.getId()));vo.setId(n.getId());vo.setCategoryId(n.getCategoryId());vo.setCategoryName(category==null?"":category);vo.setTitle(n.getTitle());vo.setStatus(n.getStatus());vo.setPublishTime(format(n.getPublishTime()));vo.setCreateTime(format(n.getCreateTime()));return vo;}
 private NoticeCategory requireCategory(Long id){if(id==null)throw new BizException("通知分类不能为空");var row=categoryMapper.selectById(id);if(row==null)throw new BizException("通知分类不存在");return row;}
 private Notice requireNotice(Long id){if(id==null)throw new BizException("通知ID不能为空");var row=noticeMapper.selectById(id);if(row==null)throw new BizException("通知不存在");return row;}
 private static void validateName(String name){if(name==null)throw new BizException("名称不能为空");String value=name.trim();int length=value.codePointCount(0,value.length());if(length<1||length>20)throw new BizException("名称需为1-20个字符");}
 private static String format(LocalDateTime value){return value==null?"":value.format(TIME);}
}
