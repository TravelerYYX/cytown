package com.yyx.ciyuantown.common.util;

import cn.hutool.core.date.DateUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具（基于 hutool，HS256）
 *
 * <p>access token 有效期短（默认 2 小时），refresh token 有效期长（默认 7 天）</p>
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${ciyuantown.jwt.secret}")
    private String secret;

    @Value("${ciyuantown.jwt.access-token-expire-seconds:7200}")
    private long accessTokenExpireSeconds;

    @Value("${ciyuantown.jwt.refresh-token-expire-seconds:604800}")
    private long refreshTokenExpireSeconds;

    private byte[] key() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    public String createAccessToken(Long userId) {
        return createAccessToken(userId, "admin");
    }

    public String createRefreshToken(Long userId) {
        return createRefreshToken(userId, "admin");
    }

    public String createAccessToken(Long userId, String audience) {
        return createToken(userId, "access", accessTokenExpireSeconds, audience,0,0);
    }

    public String createRefreshToken(Long userId, String audience) {
        return createToken(userId, "refresh", refreshTokenExpireSeconds, audience,0,0);
    }

    public String createAccessToken(Long userId,String audience,Integer version){return createToken(userId,"access",accessTokenExpireSeconds,audience,version==null?0:version,0);}
    public String createRefreshToken(Long userId,String audience,Integer version){return createToken(userId,"refresh",refreshTokenExpireSeconds,audience,version==null?0:version,0);}

    /** 带 userType 的签发重载：ut=0 超级管理员，1 官方只读账号（缺失时按超管处理） */
    public String createAccessToken(Long userId,String audience,Integer version,Integer userType){return createToken(userId,"access",accessTokenExpireSeconds,audience,version==null?0:version,userType==null?0:userType);}
    public String createRefreshToken(Long userId,String audience,Integer version,Integer userType){return createToken(userId,"refresh",refreshTokenExpireSeconds,audience,version==null?0:version,userType==null?0:userType);}

    private String createToken(Long userId, String type, long expireSeconds, String audience,int version,int userType) {
        return JWT.create()
                .setPayload("aud", audience)
                .setPayload("userId", userId)
                .setPayload("type", type)
                .setPayload("ver",version)
                .setPayload("ut",userType)
                .setPayload("jti", UUID.randomUUID().toString())
                .setExpiresAt(new Date(System.currentTimeMillis() + expireSeconds * 1000))
                .setKey(key())
                .sign();
    }

    /**
     * 校验签名与有效期，并确认 token 类型，返回 userId；校验失败返回 null
     */
    public Long verifyAndGetUserId(String token, String expectedType) {
        return verifyAndGetUserId(token, expectedType, "admin");
    }

    public Long verifyAndGetUserId(String token, String expectedType, String audience) {
        try {
            if (!JWTUtil.verify(token, key())) {
                return null;
            }
            JWTValidator.of(token).validateDate(DateUtil.date());
            JWT jwt = JWTUtil.parseToken(token);
            if (!audience.equals(jwt.getPayload("aud"))) return null;
            Object type = jwt.getPayload("type");
            if (type == null || !expectedType.equals(type.toString())) {
                return null;
            }
            Object userId = jwt.getPayload("userId");
            if (userId == null) {
                return null;
            }
            return Long.valueOf(userId.toString());
        } catch (Exception e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            return null;
        }
    }

    public Integer tokenVersion(String token){try{JWT jwt=JWTUtil.parseToken(token);Object value=jwt.getPayload("ver");return value==null?0:Integer.valueOf(value.toString());}catch(Exception e){return -1;}}
    public String tokenId(String token){try{Object value=JWTUtil.parseToken(token).getPayload("jti");return value==null?null:value.toString();}catch(Exception e){return null;}}

    /** 读取用户类型：0超级管理员，1官方只读账号；老 token 无 ut claim，按超管（0）处理 */
    public Integer userType(String token){try{Object value=JWTUtil.parseToken(token).getPayload("ut");return value==null?0:Integer.valueOf(value.toString());}catch(Exception e){return 0;}}
}
