package com.yyx.ciyuantown.business.membership;
import com.alipay.api.internal.util.AlipaySignature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.security.*;
import java.util.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MembershipPaymentTests {
 MembershipStore store;MembershipPayments gateway;MembershipService service;MembershipStore.Order order;MembershipPayments.Profile profile;
 @BeforeEach void setup(){store=mock(MembershipStore.class);gateway=mock(MembershipPayments.class);service=new MembershipService(store,gateway);
  order=new MembershipStore.Order("M"+"a".repeat(32),1L,"go",MembershipConfig.defaults().plans().getFirst(),"old-profile","APP",900,"PENDING",null,"",LocalDateTime.of(2026,11,4,10,0),LocalDateTime.of(2026,11,4,10,30),null,null,null,null);
  profile=new MembershipPayments.Profile("old-profile","APP","SANDBOX","2026000000000001","2088000000000001","PUBLIC_KEY","","","","","","https://pay.example.com/app/membership/alipay/notify","");
  when(store.claimCheck(order.id())).thenReturn(true);when(store.order(order.id())).thenReturn(order);when(store.now()).thenReturn(order.createdAt());when(gateway.get("old-profile")).thenReturn(profile);
 }
 Map<String,String> notification(){return new HashMap<>(Map.of("out_trade_no",order.id(),"trade_no","trade-1","app_id",profile.appId(),"seller_id",profile.sellerId(),"total_amount","9.00","trade_status","TRADE_SUCCESS"));}
 @Test void forgedSignatureWrongMerchantAndWrongAmountNeverGrant(){var p=notification();assertThrows(BizException.class,()->service.notify(p));verify(store,never()).paid(anyString(),anyString(),anyLong());when(gateway.verify(any(),any())).thenReturn(true);p.put("seller_id","wrong");assertThrows(BizException.class,()->service.notify(p));p.put("seller_id",profile.sellerId());p.put("app_id","wrong");assertThrows(BizException.class,()->service.notify(p));p.put("app_id",profile.appId());p.put("total_amount","8.99");assertThrows(BizException.class,()->service.notify(p));verify(store,never()).fulfill(anyString());}
 @Test void validNotificationUsesOrderProfileAndPersistsPaymentBeforeGrant(){when(gateway.verify(any(),any())).thenReturn(true);service.notify(notification());var ordered=inOrder(store);ordered.verify(store).paid(order.id(),"trade-1",900);ordered.verify(store).fulfill(order.id());verify(gateway).get("old-profile");}
 @Test void grantFailureRetainsPaidForRetry(){when(gateway.verify(any(),any())).thenReturn(true);doThrow(new BizException("failure")).when(store).fulfill(order.id());service.notify(notification());verify(store).paid(order.id(),"trade-1",900);verify(store).retry(eq(order.id()),eq("failure"));}
 @Test void unknownQueryDoesNotCloseOrGrant(){when(gateway.query(order)).thenThrow(new BizException("network"));service.reconcile(order.id());verify(store,never()).closed(anyString());verify(store,never()).paid(anyString(),anyString(),anyLong());verify(store).retry(eq(order.id()),contains("待确认"));}
 @Test void expiredOrderOnlyClosesAfterProviderConfirmation(){when(store.now()).thenReturn(order.expiresAt().plusSeconds(1));when(gateway.query(order)).thenReturn(new MembershipPayments.Trade("WAIT_BUYER_PAY","",order.id(),900));when(gateway.close(order)).thenReturn(false);service.reconcile(order.id());verify(store,never()).closed(anyString());when(gateway.close(order)).thenReturn(true);service.reconcile(order.id());verify(store).closed(order.id());}
 @Test void lostNotificationRecoveredByQuery(){when(gateway.query(order)).thenReturn(new MembershipPayments.Trade("TRADE_SUCCESS","trade-1",order.id(),900));service.reconcile(order.id());verify(store).paid(order.id(),"trade-1",900);verify(store).fulfill(order.id());}
 @Test void realRsaVerificationEncryptedVersionedProfilesAndSignedOrders() throws Exception {
  var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:membership-pay;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
  db.execute("DROP ALL OBJECTS");db.execute("CREATE TABLE membership_config(id INT PRIMARY KEY,version BIGINT,config_json LONGTEXT)");db.execute("CREATE TABLE membership_payment_profile(id VARCHAR(36) PRIMARY KEY,channel VARCHAR(8),config_json LONGTEXT)");db.execute("CREATE TABLE membership_payment_active(channel VARCHAR(8) PRIMARY KEY,profile_id VARCHAR(36))");
  var payments=new MembershipPayments(db,new ObjectMapper());ReflectionTestUtils.setField(payments,"masterKey","test-only-deployment-key-32-characters-long");
  var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);var keys=gen.generateKeyPair();String privateKey=Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()),publicKey=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
  var input=new MembershipPayments.Profile("","APP","SANDBOX",profile.appId(),profile.sellerId(),"PUBLIC_KEY",privateKey,publicKey,"","","",profile.notifyUrl(),"");
  var masked=payments.save(input);assertEquals("",masked.privateKey());String encrypted=db.queryForObject("SELECT config_json FROM membership_payment_profile WHERE id=?",String.class,masked.id());assertFalse(encrypted.contains(privateKey));assertEquals(privateKey,payments.get(masked.id()).privateKey());
  var params=notification();params.put("sign_type","RSA2");var unsigned=new HashMap<>(params);unsigned.remove("sign_type");params.put("sign",AlipaySignature.rsaSign(AlipaySignature.getSignCheckContentV1(unsigned),privateKey,"UTF-8","RSA2"));assertTrue(payments.verify(payments.get(masked.id()),params));params.put("total_amount","0.01");assertFalse(payments.verify(payments.get(masked.id()),params));
  var next=payments.save(new MembershipPayments.Profile(masked.id(),"APP","SANDBOX",profile.appId(),profile.sellerId(),"PUBLIC_KEY","",publicKey,"","","",profile.notifyUrl(),""));assertNotEquals(masked.id(),next.id());assertEquals(privateKey,payments.get(masked.id()).privateKey());
  var signed=new MembershipStore.Order(order.id(),1L,"go",order.plan(),masked.id(),"APP",900,"PENDING",null,"",order.createdAt(),order.expiresAt(),null,null,null,null);
  String orderInfo=payments.pay(signed).orderInfo();assertTrue(orderInfo.contains("alipay.trade.app.pay"));assertTrue(orderInfo.contains("sign="));assertFalse(orderInfo.contains(privateKey));
  var h5=payments.save(new MembershipPayments.Profile("","H5","SANDBOX",profile.appId(),profile.sellerId(),"PUBLIC_KEY",privateKey,publicKey,"","","",profile.notifyUrl(),"https://membership.test/#/pages/membership/subscription"));
  var h5order=new MembershipStore.Order(order.id(),1L,"go",order.plan(),h5.id(),"H5",900,"PENDING",null,"",order.createdAt(),order.expiresAt(),null,null,null,null);
  assertTrue(payments.pay(h5order).paymentUrl().startsWith("https://openapi-sandbox.dl.alipaydev.com/"));
  assertThrows(BizException.class,()->payments.save(input));
 }
}
