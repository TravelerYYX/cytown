package com.yyx.ciyuantown.business.publish.service;

import com.yyx.ciyuantown.common.storage.ResourceCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component @RequiredArgsConstructor @Slf4j
public class AssetCleanupJob {
 private final JdbcTemplate db;
 private final ResourceCleanupService cleanup;
 private final PlatformTransactionManager transactions;
 @Scheduled(cron="0 20 * * * *") public void cleanup(){
  for(Long id:db.queryForList("SELECT id FROM upload_asset WHERE attached=FALSE AND status<>'processing' AND update_time<DATE_SUB(NOW(),INTERVAL 24 HOUR) LIMIT 500",Long.class)){
   try { new TransactionTemplate(transactions).executeWithoutResult(tx -> cleanup.expireAsset(id)); }
   catch(Exception e){log.warn("Expired asset cleanup deferred: asset={}, type={}",id,e.getClass().getSimpleName());}
  }
 }
}
