package com.yyx.ciyuantown.business.membership;

import java.math.BigDecimal;

import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.business.level.LevelConfig;
import java.util.*;

public record MembershipConfig(long version, boolean selling, String notice, String contact, String agreement, List<Plan> plans) {
    public record Plan(String id, String name, String description, long priceFen, long experience, BigDecimal coins,
                       String badgeUrl, String frameUrl, boolean selling, boolean archived, List<String> benefits, boolean deleted) {
        public Plan(String id, String name, String description, long priceFen, long experience, long coins,
                    String badgeUrl, String frameUrl, boolean selling, boolean archived, List<String> benefits) {
            this(id,name,description,priceFen,experience,BigDecimal.valueOf(coins),badgeUrl,frameUrl,selling,archived,benefits,false);
        }
        public Plan removed() {return new Plan(id,name,description,priceFen,experience,coins,badgeUrl,frameUrl,false,true,benefits,true);}
    }
    public static MembershipConfig defaults() {
        var names=List.of("Go","Plus","Pro","Max","Ultra"); var plans=new ArrayList<Plan>();
        for(int i=0;i<5;i++){String id=names.get(i).toLowerCase(Locale.ROOT);String base="/uploads/membership/"+id;
            plans.add(new Plan(id,names.get(i),"星汐陪你探索次元町",900+i*1000,0,0,base+"-badge.png",base+"-frame.png",false,false,
                List.of("专属会员身份与标签","会员专属头像框")));}
        return new MembershipConfig(1,false,"","","",plans);
    }
    public void validate() {
        if(notice==null||notice.length()>500||contact==null||contact.length()>200||agreement==null||agreement.length()>50000||plans==null||plans.stream().filter(p->p==null||!p.deleted()).count()>100)
            throw new BizException("会员配置不完整或超出长度限制");
        if(selling&&agreement.isBlank())throw new BizException("请先填写付费服务协议");
        var ids=new HashSet<String>();
        for(var p:plans){
            if(p==null||p.id()==null||!p.id().matches("[a-z][a-z0-9-]{0,27}")||!ids.add(p.id())||p.name()==null||p.name().isBlank()||p.name().length()>40
                ||p.description()==null||p.description().length()>500||p.priceFen()<1||p.priceFen()>100000000||p.experience()<0||p.experience()>1000000||!com.yyx.ciyuantown.business.checkin.CheckinConfig.validCoins(p.coins())
                ||p.badgeUrl()==null||p.frameUrl()==null||(p.deleted()&&(!p.archived()||p.selling()))||p.benefits()==null||p.benefits().size()>50||p.benefits().stream().anyMatch(b->b==null||b.isBlank()||b.length()>300))throw new BizException("会员套餐配置无效");
            if(!p.badgeUrl().isBlank())LevelConfig.validateBadge(p.badgeUrl());
            if(!p.frameUrl().isBlank())LevelConfig.validateBadge(p.frameUrl());
        }
    }
}
