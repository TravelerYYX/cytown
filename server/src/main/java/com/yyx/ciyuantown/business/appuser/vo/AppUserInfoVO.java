package com.yyx.ciyuantown.business.appuser.vo;

import java.math.BigDecimal;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import java.util.List;
public record AppUserInfoVO(String userId, String publicUid, String qqNumber, String nickname, String avatar,
                            Integer level, String signature, Integer gender, String cardBackground, List<UserTagVO> tags,
                            Boolean hasPassword, String account, Boolean isOfficial, String avatarFrame,Long avatarFrameExpiresAt,BigDecimal coinBalance) {
    public static AppUserInfoVO from(AppUser u) { return from(u, List.of()); }
    public static AppUserInfoVO from(AppUser u, List<UserTagVO> tags) {
        return from(u,tags,u.getUsername()==null?u.getQqNumber():u.getUsername(),u.getOfficialAdminId()!=null);
    }
    public static AppUserInfoVO from(AppUser u, List<UserTagVO> tags, String account, boolean official) {
        return new AppUserInfoVO(u.getId().toString(), u.getPublicUid()==null?null:u.getPublicUid().toString(),
                u.getQqNumber(), u.getNickname(), u.getAvatar(), u.getLevel(),
                u.getSignature()==null?"":u.getSignature(), u.getGender()==null?2:u.getGender(),
                u.getCardBackground()==null||u.getCardBackground().isBlank()?"01.png":u.getCardBackground(),
                tags==null?List.of():tags,
                official || (u.getPassword()!=null&&!u.getPassword().isBlank()), account, official, u.getAvatarFrame()==null?"":u.getAvatarFrame(),u.getAvatarFrameExpiresAt(),u.getCoinBalance()==null?BigDecimal.ZERO:u.getCoinBalance());
    }
}
