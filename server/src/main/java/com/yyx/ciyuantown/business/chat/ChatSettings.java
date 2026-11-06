package com.yyx.ciyuantown.business.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.NotNull;

@Service @RequiredArgsConstructor
public class ChatSettings {
 private static final boolean DEFAULT_OFFLINE_REMINDER_ENABLED=false;
 private final JdbcTemplate db;
 private final ObjectMapper json;
 public record Images(int maxEdge,int quality,int pngCompression) {}
 public record Videos(int maxHeight,int maxFps,int crf,int audioKbps,int maxKbps,int concurrency,int coverMaxEdge,int coverQuality) {}
 public record Config(int revokeSeconds,Images images,Videos videos) {}
 public record OfflineReminder(@NotNull Boolean enabled) {}
 public OfflineReminder offlineReminder(){
  var rows=db.queryForList("SELECT config_value FROM system_config WHERE config_key='chat_offline_reminder_enabled'",String.class);
  if(rows.isEmpty())return new OfflineReminder(DEFAULT_OFFLINE_REMINDER_ENABLED);
  return new OfflineReminder("1".equals(rows.getFirst()));
 }
 @Transactional public OfflineReminder saveOfflineReminder(OfflineReminder reminder){
  if(reminder==null||reminder.enabled()==null)throw new BizException("请选择是否开启私聊离线消息提醒");
  db.update("INSERT INTO system_config(config_key,config_value,remark) VALUES('chat_offline_reminder_enabled',?,'私聊离线消息提醒') ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",reminder.enabled()?"1":"0");
  if(!reminder.enabled())db.update("UPDATE chat_push_task SET state='cancelled',lease_until=0 WHERE state IN ('pending','sending')");
  return offlineReminder();
 }
 public static Config defaults(){return new Config(120,new Images(2560,85,6),new Videos(1080,30,23,128,5000,1,1280,78));}
 public Config get(){var rows=db.queryForList("SELECT config_value FROM system_config WHERE config_key='chat_settings'",String.class);if(rows.isEmpty())return defaults();try{return json.readValue(rows.getFirst(),Config.class);}catch(Exception e){throw new BizException("私聊配置无法读取");}}
 public Config save(Config c){
  if(c==null||c.images()==null||c.videos()==null)throw new BizException("请完整填写私聊配置");
  range(c.revokeSeconds(),0,86400);var i=c.images();var v=c.videos();range(i.maxEdge(),64,16384);range(i.quality(),1,100);range(i.pngCompression(),0,9);
  range(v.maxHeight(),144,2160);range(v.maxFps(),1,60);range(v.crf(),0,40);range(v.audioKbps(),32,320);range(v.maxKbps(),100,30000);range(v.concurrency(),1,4);range(v.coverMaxEdge(),64,4096);range(v.coverQuality(),1,100);
  try{db.update("INSERT INTO system_config(config_key,config_value,remark) VALUES('chat_settings',?,'私聊独立配置') ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",json.writeValueAsString(c));return c;}catch(Exception e){throw new BizException("私聊配置保存失败");}
 }
 private static void range(int n,int min,int max){if(n<min||n>max)throw new BizException("私聊参数超出范围："+min+"–"+max);}
}
