package com.yyx.ciyuantown.business.membership;

import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class MembershipPayments {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    @Value("${MEMBERSHIP_CONFIG_KEY:}") private String masterKey;
    public record Profile(String id,String channel,String environment,String appId,String sellerId,String mode,String privateKey,
                          String publicKey,String appCert,String alipayCert,String rootCert,String notifyUrl,String returnUrl) {}
    public record Trade(String status,String tradeNo,String orderId,long amountFen) {}
    public record PayData(String orderId,String channel,String orderInfo,String paymentUrl) {}
    public List<Profile> list(){return List.of(mask(activeOrEmpty("APP")),mask(activeOrEmpty("H5")));}
    private Profile activeOrEmpty(String channel){var ids=db.queryForList("SELECT profile_id FROM membership_payment_active WHERE channel=?",String.class,channel);
        return ids.isEmpty()?new Profile("",channel,"SANDBOX","","","PUBLIC_KEY","","","","","","",""):get(ids.getFirst());}
    public Profile active(String channel){var p=activeOrEmpty(channel);if(p.id().isEmpty())throw new BizException("该支付渠道未配置");return p;}
    public Profile get(String id){var rows=db.queryForList("SELECT config_json FROM membership_payment_profile WHERE id=?",String.class,id);
        if(rows.isEmpty())throw new BizException("支付配置不存在");try{var p=json.readValue(rows.getFirst(),Profile.class);return withKey(p,crypt(p.privateKey(),false));}
        catch(Exception e){throw new BizException("支付配置无法解密，请检查部署密钥");}}
    private Profile mask(Profile p){return withKey(p,"");}
    private Profile withKey(Profile p,String key){return new Profile(p.id(),p.channel(),p.environment(),p.appId(),p.sellerId(),p.mode(),key,p.publicKey(),p.appCert(),p.alipayCert(),p.rootCert(),p.notifyUrl(),p.returnUrl());}
    @Transactional public Profile save(Profile input){
        if(input==null||!Set.of("APP","H5").contains(input.channel()))throw new BizException("支付渠道无效");
        try{db.update("INSERT IGNORE INTO membership_config(id,version,config_json) VALUES(1,1,?)",json.writeValueAsString(MembershipConfig.defaults()));}catch(Exception e){throw new BizException("请先执行会员数据库迁移");}
        db.queryForList("SELECT id FROM membership_config WHERE id=1 FOR UPDATE");
        Profile old=activeOrEmpty(input.channel());
        if(!Objects.equals(old.id(),input.id()))throw new BizException("支付配置已更新，请重新加载");
        var p=new Profile(UUID.randomUUID().toString(),input.channel(),input.environment(),input.appId(),input.sellerId(),input.mode(),
            input.privateKey()==null||input.privateKey().isBlank()?old.privateKey():input.privateKey(),input.publicKey(),input.appCert(),input.alipayCert(),input.rootCert(),input.notifyUrl(),input.returnUrl());
        validate(p);client(p);
        try{db.update("INSERT INTO membership_payment_profile(id,channel,config_json) VALUES(?,?,?)",p.id(),p.channel(),json.writeValueAsString(withKey(p,crypt(p.privateKey(),true))));}
        catch(Exception e){throw new BizException("支付配置保存失败，请检查部署密钥");}
        db.update("INSERT INTO membership_payment_active(channel,profile_id) VALUES(?,?) ON DUPLICATE KEY UPDATE profile_id=VALUES(profile_id)",p.channel(),p.id());
        return mask(p);
    }
    public void validate(Profile p){
        if(masterKey==null||masterKey.length()<32)throw new BizException("服务端须设置至少32字符的 MEMBERSHIP_CONFIG_KEY");
        if(!Set.of("PRODUCTION","SANDBOX").contains(p.environment())||!Set.of("PUBLIC_KEY","CERT").contains(p.mode())||p.appId()==null||!p.appId().matches("[0-9]{10,32}")||p.sellerId()==null||!p.sellerId().matches("[0-9]{10,32}")||p.privateKey()==null||p.privateKey().isBlank())throw new BizException("请填写正确的环境、APPID、PID和应用私钥");
        https(p.notifyUrl(),false);if("H5".equals(p.channel()))https(p.returnUrl(),true);
        try{
            String message="membership-config-check";
            String signature=AlipaySignature.rsaSign(message,p.privateKey(),"UTF-8","RSA2");
            if("CERT".equals(p.mode())){
                var factory=CertificateFactory.getInstance("X.509");
                var app=(X509Certificate)factory.generateCertificate(new ByteArrayInputStream(p.appCert().getBytes(StandardCharsets.UTF_8)));
                if(!AlipaySignature.rsaCheck(message,signature,Base64.getEncoder().encodeToString(app.getPublicKey().getEncoded()),"UTF-8","RSA2"))throw new IllegalArgumentException();
                factory.generateCertificate(new ByteArrayInputStream(p.alipayCert().getBytes(StandardCharsets.UTF_8)));
                if(factory.generateCertificates(new ByteArrayInputStream(p.rootCert().getBytes(StandardCharsets.UTF_8))).isEmpty())throw new IllegalArgumentException();
            }else KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(p.publicKey().replaceAll("-----[^-]+-----|\\s", ""))));
        }catch(Exception e){throw new BizException("私钥、公钥或证书格式无效，或应用证书与私钥不匹配");}
    }
    private void https(String url,boolean allowFragment){try{var u=URI.create(url);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||(!allowFragment&&u.getFragment()!=null))throw new IllegalArgumentException();}catch(Exception e){throw new BizException("回调和返回地址必须是有效的公网 HTTPS 地址");}}
    public AlipayClient client(Profile p){try{var c=new AlipayConfig();c.setServerUrl("PRODUCTION".equals(p.environment())?"https://openapi.alipay.com/gateway.do":"https://openapi-sandbox.dl.alipaydev.com/gateway.do");c.setAppId(p.appId());c.setPrivateKey(p.privateKey());c.setFormat("json");c.setCharset("UTF-8");c.setSignType("RSA2");c.setConnectTimeout(5000);c.setReadTimeout(10000);
        if("CERT".equals(p.mode())){c.setAppCertContent(p.appCert());c.setAlipayPublicCertContent(p.alipayCert());c.setRootCertContent(p.rootCert());}else c.setAlipayPublicKey(p.publicKey());return new DefaultAlipayClient(c);
    }catch(Exception e){throw new BizException("无法初始化支付宝配置");}}
    public record TradeRequest(String id,String profileId,String channel,long amountFen,String subject,LocalDateTime expiresAt,String returnUrl) {}
    private TradeRequest trade(MembershipStore.Order order) {
        return new TradeRequest(order.id(),order.profileId(),order.channel(),order.amountFen(),
                order.plan().name()+" 一个月会员",order.expiresAt(),null);
    }
    public PayData pay(MembershipStore.Order order){return pay(trade(order));}
    public PayData pay(TradeRequest order){var p=get(order.profileId());try{
        var biz=new LinkedHashMap<String,Object>();biz.put("out_trade_no",order.id());biz.put("total_amount",BigDecimal.valueOf(order.amountFen(),2).toPlainString());biz.put("subject",order.subject());biz.put("seller_id",p.sellerId());
        biz.put("time_expire",order.expiresAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        if("APP".equals(order.channel())){biz.put("product_code","QUICK_MSECURITY_PAY");var r=new AlipayTradeAppPayRequest();r.setBizContent(json.writeValueAsString(biz));r.setNotifyUrl(p.notifyUrl());return new PayData(order.id(),"APP",client(p).sdkExecute(r).getBody(),"");}
        biz.put("product_code","QUICK_WAP_WAY");var r=new AlipayTradeWapPayRequest();r.setBizContent(json.writeValueAsString(biz));r.setNotifyUrl(p.notifyUrl());r.setReturnUrl(order.returnUrl()==null?p.returnUrl():order.returnUrl());return new PayData(order.id(),"H5","",client(p).pageExecute(r,"GET").getBody());
    }catch(Exception e){throw new BizException("支付宝支付发起失败，请检查渠道配置");}}
    public boolean verify(Profile p,Map<String,String> params){try{
        String key=p.publicKey();if("CERT".equals(p.mode()))key=Base64.getEncoder().encodeToString(CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(p.alipayCert().getBytes(StandardCharsets.UTF_8))).getPublicKey().getEncoded());
        return "RSA2".equals(params.get("sign_type"))&&AlipaySignature.rsaCheckV1(params,key,"UTF-8","RSA2");
    }catch(Exception e){return false;}}
    public Trade query(MembershipStore.Order o){return query(trade(o));}
    public Trade query(TradeRequest o){var p=get(o.profileId());try{var r=new AlipayTradeQueryRequest();r.setBizContent(json.writeValueAsString(Map.of("out_trade_no",o.id())));var response=client(p).execute(r);
        if(!response.isSuccess()){if("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode()))return new Trade("NOT_FOUND","",o.id(),0);throw new BizException("支付宝交易查询失败");}
        return new Trade(response.getTradeStatus(),response.getTradeNo(),response.getOutTradeNo(),new BigDecimal(response.getTotalAmount()).movePointRight(2).longValueExact());
    }catch(BizException e){throw e;}catch(Exception e){throw new BizException("支付宝交易查询暂不可用");}}
    public boolean close(MembershipStore.Order o){return close(trade(o));}
    public boolean close(TradeRequest o){try{var r=new AlipayTradeCloseRequest();r.setBizContent(json.writeValueAsString(Map.of("out_trade_no",o.id())));var result=client(get(o.profileId())).execute(r);return result.isSuccess()||"ACQ.TRADE_NOT_EXIST".equals(result.getSubCode())||"ACQ.TRADE_HAS_CLOSE".equals(result.getSubCode());}catch(Exception e){return false;}}
    private String crypt(String value,boolean encrypt)throws Exception{
        if(masterKey==null||masterKey.length()<32)throw new IllegalStateException();
        var key=new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8)),"AES");var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        if(encrypt){byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));var body=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));var packed=new byte[iv.length+body.length];System.arraycopy(iv,0,packed,0,iv.length);System.arraycopy(body,0,packed,iv.length,body.length);return Base64.getEncoder().encodeToString(packed);}
        byte[] all=Base64.getDecoder().decode(value);cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(all,0,12)));return new String(cipher.doFinal(Arrays.copyOfRange(all,12,all.length)),StandardCharsets.UTF_8);
    }
}
