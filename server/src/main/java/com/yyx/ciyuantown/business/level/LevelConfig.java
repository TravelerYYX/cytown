package com.yyx.ciyuantown.business.level;

import com.yyx.ciyuantown.common.exception.BizException;
import java.net.URI;
import java.util.*;

public record LevelConfig(long version, List<Tier> tiers, List<Level> levels, List<Rule> rules) {
    public record Tier(int id, int minLevel, int maxLevel, String title, String fullTitle,
                       String color, String description, long entryBonus) {}
    public record Level(int level, long requiredExp, String badgeUrl) {}
    /** dailyLimit is an experience limit; zero disables the reward. */
    public record Rule(String action, String name, long experience, long dailyLimit, boolean enabled) {}
    public static final Set<String> ACTIONS = Set.of("sign_in", "streak_7", "comment", "post", "received_like");
    public static final long MAX_EXP = 1_000_000_000_000L;

    public static LevelConfig defaults() {
        String[] titles={"初旅","循迹","逐曜","织星","耀令","归穹"};
        String[] full={"星槎新客","开拓行者","星芒逐客","命途织者","星穹使官","寰宇星主"};
        String[] colors={"#8393a6","#22a7ef","#5645dc","#cf278e","#e3314b","#b2baff"};
        String[] descriptions={"社区新人，初入次元世界","活跃用户，熟悉社区生态","创作入门，开始产出内容", "中坚创作者，稳定输出优质内容","社区核心大佬，作品具备广泛影响力","社区最高荣誉，传说级共建者"};
        List<Tier> tiers=new ArrayList<>(); List<Level> levels=new ArrayList<>();
        for(int i=0;i<6;i++) tiers.add(new Tier(i+1,i*10+1,i*10+10,titles[i],titles[i]+" · "+full[i],colors[i],descriptions[i],i==0?0:50));
        for(int n=1;n<=60;n++) levels.add(new Level(n,100L*(n-1)+15L*(n-1)*(n-2),"/uploads/levels/level-"+n+".png"));
        return new LevelConfig(0,tiers,levels,List.of(
            new Rule("sign_in","每日签到",10,10,true),new Rule("streak_7","连续7天签到",30,30,true),
            new Rule("comment","发表评论",2,20,true),new Rule("post","发布普通帖子",20,60,true),
            new Rule("received_like","内容被点赞",1,50,true)));
    }

    public void validate() {
        if(tiers==null||tiers.size()!=6||levels==null||levels.size()!=60||rules==null||rules.size()!=5)
            fail("必须完整配置六个段位、60个等级和五条经验规则");
        int next=1;
        for(int i=0;i<tiers.size();i++) {
            Tier t=tiers.get(i);
            if(t==null||t.id()!=i+1||t.minLevel()!=next||t.maxLevel()<next||t.maxLevel()>60) fail("段位区间必须按顺序连续覆盖1-60级");
            next=t.maxLevel()+1;
            text(t.title(),20,"头衔");text(t.fullTitle(),60,"拓展全称");text(t.description(),500,"定位说明");
            if(t.color()==null||!t.color().matches("#[0-9a-fA-F]{6}"))fail("色系需为六位十六进制颜色");
            if(t.entryBonus()<0||t.entryBonus()>1_000_000||i==0&&t.entryBonus()!=0)fail("跨段奖励需为0-1000000，首段奖励必须为0");
        }
        if(next!=61)fail("段位区间必须覆盖至60级");
        long previous=-1;
        for(int i=0;i<levels.size();i++) {
            Level l=levels.get(i);
            if(l==null||l.level()!=i+1||l.requiredExp()<=previous||l.requiredExp()>MAX_EXP||i==0&&l.requiredExp()!=0)
                fail("等级须按1-60排序，1级经验为0，其余累计经验严格递增");
            previous=l.requiredExp();validateBadge(l.badgeUrl());
        }
        Set<String> seen=new HashSet<>();
        for(Rule r:rules) {
            if(r==null||!ACTIONS.contains(r.action())||!seen.add(r.action()))fail("经验规则类型缺失或重复");
            text(r.name(),40,"行为名称");
            if(r.experience()<0||r.experience()>1_000_000||r.dailyLimit()<0||r.dailyLimit()>10_000_000)fail("经验或每日上限超出范围");
        }
    }
    public static void validateBadge(String url) {
        if(url==null||url.length()>2048)fail("徽章图片地址无效");
        if(url.matches("/uploads/[a-zA-Z0-9_./-]+")&&!url.contains(".."))return;
        try {URI u=URI.create(url);if(Set.of("http","https").contains(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null)return;}catch(Exception ignored){}
        fail("徽章需使用已上传图片或HTTP/HTTPS图片地址");
    }
    private static void text(String s,int max,String label) {if(s==null||s.isBlank()||s.codePointCount(0,s.length())>max)fail(label+"不能为空且不能超过"+max+"字");}
    private static void fail(String message) {throw new BizException(message);}
    public Level levelFor(long experience) {
        Level current=levels.getFirst();for(Level l:levels) {if(l.requiredExp()>experience)break;current=l;}return current;
    }
    public Tier tierFor(int level) {return tiers.stream().filter(t->level>=t.minLevel()&&level<=t.maxLevel()).findFirst().orElseThrow();}
    public Rule rule(String action) {return rules.stream().filter(r->r.action().equals(action)).findFirst().orElseThrow();}
}
