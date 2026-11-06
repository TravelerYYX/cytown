package com.yyx.ciyuantown.business.appuser.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserLocationServiceTests {
    JdbcTemplate db;UserLocationService locations;MockHttpServletRequest request;
    @BeforeEach void setup() {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:location;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("DROP ALL OBJECTS");
        db.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY,status INT DEFAULT 0,deleted BOOLEAN DEFAULT FALSE,ip_province VARCHAR(128),ip_located_at BIGINT)");
        db.execute("INSERT INTO app_user(id) VALUES(1),(2)");
        locations=spy(new UserLocationService(db,new ObjectMapper(),"https://ip.moonya.cn/api/locate","test-only","127.0.0.1,::1"));
        request=new MockHttpServletRequest();request.setRemoteAddr("8.8.8.8");
    }
    @Test void recordsTargetAccountAndThrottlesSuccessfulUpdates() throws Exception {
        doReturn("江苏").when(locations).locate("8.8.8.8");
        assertEquals("江苏",locations.refresh(1,request).province());
        assertNotNull(locations.read(1).locatedAt());assertNull(locations.read(2).province());
        locations.refresh(1,request);verify(locations,times(1)).locate("8.8.8.8");
    }
    @Test void failureRetainsPreviousProvinceAndNeverUsesServerEgress() throws Exception {
        db.update("UPDATE app_user SET ip_province='山东',ip_located_at=1 WHERE id=1");
        doThrow(new java.io.IOException("offline")).when(locations).locate(anyString());
        assertEquals("山东",locations.refresh(1,request).province());
        request.setRemoteAddr("127.0.0.1");locations.refresh(2,request);
        verify(locations,times(1)).locate(anyString());assertNull(locations.read(2).province());
    }
    @Test void onlyTrustedProxyHeadersAreUsed() throws Exception {
        request.addHeader("X-Forwarded-For","1.1.1.1");assertEquals("8.8.8.8",locations.clientIp(request));
        request.setRemoteAddr("127.0.0.1");assertEquals("1.1.1.1",locations.clientIp(request));
        request.removeHeader("X-Forwarded-For");request.addHeader("X-Forwarded-For","2.2.2.2, 8.8.4.4, 127.0.0.1");
        assertEquals("8.8.4.4",locations.clientIp(request));
    }
    @Test void privateAndMalformedAddressesAreNotLocated() {
        for(String ip:new String[]{"127.0.0.1","10.0.0.1","172.16.0.1","192.168.1.1","::1","fc00::1","fe80::1","100.64.0.1","localhost","bad.example"})assertFalse(UserLocationService.publicIp(ip),ip);
        assertTrue(UserLocationService.publicIp("8.8.8.8"));assertTrue(UserLocationService.publicIp("2001:4860:4860::8888"));
    }
    @Test void disabledAccountsCannotReceiveLocationUpdates() throws Exception {
        db.update("UPDATE app_user SET deleted=TRUE WHERE id=1");doReturn("江苏").when(locations).locate(anyString());
        assertNull(locations.refresh(1,request).province());assertNull(db.queryForObject("SELECT ip_province FROM app_user WHERE id=1",String.class));
    }
}
