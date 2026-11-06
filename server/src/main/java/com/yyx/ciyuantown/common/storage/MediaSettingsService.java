package com.yyx.ciyuantown.common.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class MediaSettingsService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public record Images(int coverMaxEdge,int coverQuality,int previewMaxEdge,int previewQuality,int pngCompression,long cacheMaxBytes) {}
    public record Videos(int segmentSeconds,int maxFps,int audioKbps,int crf,int concurrency,Map<String,Integer> bitrates) {}
    public record Settings(Images images,Videos videos) {}
    public static Settings defaults() { return new Settings(new Images(1280,78,2560,85,6,3L*1024*1024*1024),new Videos(4,30,128,23,1,Map.of("1080",5000,"720",2800,"480",1400,"360",800))); }
    public Settings get() {
        var rows=db.queryForList("SELECT config_value FROM system_config WHERE config_key='media_processing'",String.class);
        if(rows.isEmpty())return defaults();
        try{return json.readValue(rows.getFirst(),Settings.class);}catch(Exception e){throw new BizException("媒体配置无法读取，请重新保存配置");}
    }
    public Settings save(Settings value) {
        if(value==null||value.images()==null||value.videos()==null)throw new BizException("媒体配置不能为空");
        var i=value.images();var v=value.videos();
        range(i.coverMaxEdge(),64,8192);range(i.previewMaxEdge(),64,16384);range(i.coverQuality(),1,100);range(i.previewQuality(),1,100);range(i.pngCompression(),0,9);
        if(i.cacheMaxBytes()<16L*1024*1024||i.cacheMaxBytes()>32L*1024*1024*1024)throw new BizException("缓存上限需为16MB至32GB");
        range(v.segmentSeconds(),2,10);range(v.maxFps(),1,30);range(v.audioKbps(),32,320);range(v.crf(),0,40);range(v.concurrency(),1,8);
        if(v.bitrates()==null||!v.bitrates().keySet().equals(Set.of("1080","720","480","360")))throw new BizException("请配置四档视频码率");
        for(int rate:v.bitrates().values())range(rate,100,20000);
        try{db.update("INSERT INTO system_config(config_key,config_value,remark) VALUES('media_processing',?,'媒体压缩与缓存配置') ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",json.writeValueAsString(value));return value;}catch(Exception e){throw new BizException("媒体配置保存失败");}
    }
    private static void range(int value,int min,int max){if(value<min||value>max)throw new BizException("压缩参数超出允许范围："+min+"-"+max);}
}
