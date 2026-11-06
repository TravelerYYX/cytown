package com.yyx.ciyuantown.business.homeconfig.service;
import com.yyx.ciyuantown.business.homeconfig.dto.*;
import com.yyx.ciyuantown.business.homeconfig.entity.*;
import java.util.List;
public interface HomeConfigService {
 List<HomeChannel> channels(boolean enabledOnly); HomeChannel saveChannel(HomeChannelParam param, boolean create); void deleteChannel(Long id); void sortChannels(List<Long> ids);
 List<HomeEntry> entries(boolean enabledOnly); HomeEntry saveEntry(HomeEntryParam param, boolean create); void deleteEntry(Long id); void sortEntries(List<Long> ids);
 NewsConfig newsConfig(); NewsConfig updateNews(NewsConfigParam param); AppHomeConfig appConfig();
 LayoutConfig layoutConfig(); LayoutConfig updateLayout(LayoutConfigParam param);
 HeaderEntryParam headerEntry(); HeaderEntryParam updateHeaderEntry(HeaderEntryParam param);
 record LayoutConfig(String defaultFeedLayout) {}
 record ChannelItem(Long id,String name,boolean showAllPosts) {}
 record EntryItem(Long id,String name,String iconUrl,String targetType,String linkUrl,String openMode,Boolean showTopbar) {}
 String DEFAULT_EMPTY_TEXT = "暂无资讯，敬请期待";
 record NewsConfig(boolean enabled,int interval,String emptyText) {}
 record NewsItem(Long postId,String title) {}
 record News(boolean enabled,int interval,String emptyText,List<NewsItem> items) {}
 record AppHomeConfig(List<ChannelItem> channels,List<EntryItem> entries,News news,String defaultFeedLayout,HeaderEntryParam headerEntry) {}
}
