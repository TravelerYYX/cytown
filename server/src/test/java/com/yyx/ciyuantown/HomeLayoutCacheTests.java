package com.yyx.ciyuantown;

import com.yyx.ciyuantown.business.homeconfig.dto.LayoutConfigParam;
import com.yyx.ciyuantown.business.homeconfig.entity.SystemConfig;
import com.yyx.ciyuantown.business.homeconfig.mapper.*;
import com.yyx.ciyuantown.business.homeconfig.service.HomeConfigService;
import com.yyx.ciyuantown.business.homeconfig.service.impl.HomeConfigServiceImpl;
import com.yyx.ciyuantown.business.post.mapper.PostMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringJUnitConfig(HomeLayoutCacheTests.Config.class)
class HomeLayoutCacheTests {
    @Configuration @EnableCaching static class Config {
        @Bean CacheManager cacheManager(){return new ConcurrentMapCacheManager("home-config");}
        @Bean HomeConfigService home(){
            var channels=mock(HomeChannelMapper.class);var entries=mock(HomeEntryMapper.class);
            var configs=mock(SystemConfigMapper.class);var posts=mock(PostMapper.class);
            when(channels.selectList(any())).thenReturn(List.of());
            when(entries.selectList(any())).thenReturn(List.of());
            when(posts.selectList(any())).thenReturn(List.of());
            var stored=new ConcurrentHashMap<String,SystemConfig>();
            when(configs.selectById(any())).thenAnswer(call -> {
                if(stored.containsKey(call.getArgument(0))) return stored.get(call.getArgument(0));
                if("news_enabled".equals(call.getArgument(0))) {
                    var disabled=new SystemConfig();disabled.setConfigKey("news_enabled");disabled.setConfigValue("0");return disabled;
                }
                return null;
            });
            when(configs.insert(any(SystemConfig.class))).thenAnswer(call -> {SystemConfig row=call.getArgument(0);stored.put(row.getConfigKey(),row);return 1;});
            when(configs.updateById(any(SystemConfig.class))).thenAnswer(call -> {SystemConfig row=call.getArgument(0);stored.put(row.getConfigKey(),row);return 1;});
            return new HomeConfigServiceImpl(org.mockito.Mockito.mock(com.yyx.ciyuantown.common.storage.ResourceCleanupService.class),channels,entries,configs,posts);
        }
    }
    @Autowired HomeConfigService home;
    @Test void headerUpdatesAndDisablingEvictCachedAppConfig() {
        var initial=home.appConfig();
        assertNull(initial.headerEntry());
        home.updateHeaderEntry(new com.yyx.ciyuantown.business.homeconfig.dto.HeaderEntryParam("设置","/icon.png","app","/pages/settings/index","inapp",true,true));
        var updated=home.appConfig();
        assertNotSame(initial,updated);
        assertEquals("/pages/settings/index",updated.headerEntry().linkUrl());
        assertSame(updated,home.appConfig());
        home.updateHeaderEntry(new com.yyx.ciyuantown.business.homeconfig.dto.HeaderEntryParam("设置","/icon.png","app","/pages/settings/index","inapp",true,false));
        assertNull(home.appConfig().headerEntry());
    }
    @Test void updatingDefaultEvictsCachedAppConfig() {
        var initial=home.appConfig();
        assertSame(initial,home.appConfig());
        assertEquals("single",initial.defaultFeedLayout());
        home.updateLayout(new LayoutConfigParam("waterfall"));
        var updated=home.appConfig();
        assertNotSame(initial,updated);
        assertEquals("waterfall",updated.defaultFeedLayout());
        home.updateLayout(new LayoutConfigParam("single"));
        assertEquals("single",home.appConfig().defaultFeedLayout());
    }
}
