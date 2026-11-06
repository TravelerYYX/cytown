package com.yyx.ciyuantown.business.checkin;

import java.math.BigDecimal;

import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.business.level.LevelConfig;
import java.util.*;

public record CheckinConfig(long version, BigDecimal makeupPrice, Asset coinIcon, Asset experienceIcon,
                            List<Frame> frames, List<Day> days) {
    public record Asset(String url, long originalBytes, long processedBytes) {}
    public record Frame(String id, String name, Asset image) {}
    public record Day(int day, BigDecimal coins, long experience, String frameId, int frameDays, String primary) {
        public Day(int day,long coins,long experience,String frameId,int frameDays,String primary) {
            this(day,BigDecimal.valueOf(coins),experience,frameId,frameDays,primary);
        }
    }
    public CheckinConfig(long version,long makeupPrice,Asset coinIcon,Asset experienceIcon,List<Frame> frames,List<Day> days) {
        this(version,BigDecimal.valueOf(makeupPrice),coinIcon,experienceIcon,frames,days);
    }
    public static boolean validCoins(BigDecimal coins) {
        return coins!=null && coins.signum()>=0 && coins.compareTo(BigDecimal.valueOf(1_000_000))<=0
            && coins.stripTrailingZeros().scale()<=1;
    }
    public static CheckinConfig defaults() {
        var days=new ArrayList<Day>();
        for(int d=1;d<=31;d++) days.add(new Day(d,10,10,"",0,"coins"));
        return new CheckinConfig(1,10,new Asset("/uploads/checkin/coin.webp",0,0),
            new Asset("/uploads/checkin/experience.webp",0,0),
            List.of(new Frame("xingxi", "星汐 · 星愿",new Asset("/uploads/checkin/frame.webp",0,0))),days);
    }
    public void validate() {
        if(!validCoins(makeupPrice)) fail("补签价格须为0至1000000");
        asset(coinIcon);asset(experienceIcon);
        if(frames==null||frames.size()>100||days==null||days.size()!=31)fail("请完整配置31天奖励，头像框最多100个");
        Set<String> ids=new HashSet<>();
        for(var f:frames){
            if(f==null||f.id()==null||!f.id().matches("[a-z][a-z0-9-]{0,31}")||Set.of("star-sea","sakura-mist").contains(f.id())||f.id().startsWith("shop-")||!ids.add(f.id()))fail("头像框标识无效或重复");
            if(f.name()==null||f.name().isBlank()||f.name().length()>40)fail("头像框名称须为1至40字");asset(f.image());
        }
        for(int i=0;i<31;i++){
            var d=days.get(i);
            if(d==null||d.day()!=i+1||!validCoins(d.coins())||d.experience()<0||d.experience()>1_000_000||d.frameId()==null||d.frameDays()<0||d.frameDays()>36500)fail("每日奖励配置无效");
            boolean frame=!d.frameId().isEmpty();
            if(frame&&!ids.contains(d.frameId()))fail("奖励头像框不存在");
            if(!Set.of("coins","experience","frame").contains(d.primary())||("coins".equals(d.primary())&&d.coins().signum()==0)||("experience".equals(d.primary())&&d.experience()==0)||("frame".equals(d.primary())&&!frame))fail("主奖励须选择当日实际发放的一项");
        }
    }
    private static void asset(Asset a){if(a==null||a.originalBytes()<0||a.processedBytes()<0)fail("图片信息无效");LevelConfig.validateBadge(a.url());}
    private static void fail(String message){throw new BizException(message);}
}
