package com.yyx.ciyuantown.business.post.service;

import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;

@Service @RequiredArgsConstructor
public class PostShareSettings {
 private final JdbcTemplate db;
 public record Config(String baseUrl) {}
 public Config get(){var rows=db.queryForList("SELECT config_value FROM system_config WHERE config_key='post_share_base_url'",String.class);return new Config(rows.isEmpty()?"":rows.getFirst());}
 @Transactional public Config save(Config input){
  if(input==null||input.baseUrl()==null)throw new BizException("请填写分享域名，或留空使用默认地址");
  String value=input.baseUrl().trim().replaceAll("/+$","");
  if(!value.isEmpty())try{var url=URI.create(value);if(value.length()>500||!java.util.Set.of("http","https").contains(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null||url.getQuery()!=null||url.getFragment()!=null||url.getRawPath()!=null&&!url.getRawPath().isEmpty())throw new IllegalArgumentException();}catch(Exception e){throw new BizException("请填写有效的 HTTP 或 HTTPS 域名，不包含路径、参数或账号");}
  db.update("INSERT INTO system_config(config_key,config_value,remark) VALUES('post_share_base_url',?,'帖子分享域名，留空使用当前后端地址') ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",value);
  return new Config(value);
 }
}
