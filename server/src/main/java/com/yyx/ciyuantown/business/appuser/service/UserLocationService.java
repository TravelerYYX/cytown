package com.yyx.ciyuantown.business.appuser.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class UserLocationService {
    public record Location(String province,Long locatedAt) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String endpoint,key;
    private final Set<String> proxies;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Object[] locks=new Object[64];
    public UserLocationService(JdbcTemplate db,ObjectMapper json,
        @Value("${IP_LOCATE_URL:https://ip.moonya.cn/api/locate}") String endpoint,
        @Value("${IP_LOCATE_KEY:}") String key,
        @Value("${IP_LOCATE_TRUSTED_PROXIES:127.0.0.1,::1}") String proxies) {
        this.db=db;this.json=json;this.endpoint=endpoint;this.key=key;
        this.proxies=new HashSet<>(Arrays.asList(proxies.split(",")));
        Arrays.setAll(locks,i->new Object());
    }
    public Location read(long user) {
        var rows=db.query("SELECT ip_province,ip_located_at FROM app_user WHERE id=? AND status=0 AND deleted=0",
            (r,n)->new Location(r.getString(1),r.getObject(2,Long.class)),user);
        return rows.isEmpty()?new Location(null,null):rows.getFirst();
    }
    public Location refresh(long user,HttpServletRequest request) {
        synchronized(locks[Math.floorMod(Long.hashCode(user),locks.length)]) {
            var old=read(user);long now=System.currentTimeMillis();
            if(old.locatedAt()!=null&&now-old.locatedAt()<Duration.ofHours(24).toMillis())return old;
            String ip=clientIp(request);
            if(key.isBlank()||!publicIp(ip))return old;
            try {
                String province=locate(ip);
                if(province!=null&&!province.isBlank()&&!province.contains("未知")) {
                    int changed=db.update("UPDATE app_user SET ip_province=?,ip_located_at=? WHERE id=? AND status=0 AND deleted=0 AND (ip_located_at IS NULL OR ip_located_at<=?)",
                        province,now,user,now-Duration.ofHours(24).toMillis());
                    return changed>0?new Location(province,now):read(user);
                }
            } catch(Exception ignored) { /* A locator outage must not break account or profile access. */ }
            return old;
        }
    }
    public String locate(String ip) throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create(endpoint+"?ip="+URLEncoder.encode(ip,java.nio.charset.StandardCharsets.UTF_8)))
            .timeout(Duration.ofSeconds(3)).header("X-API-Key",key).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)return null;
        var value=json.readTree(response.body());
        return value.path("code").asInt(-1)==0?value.path("province").asText(null):null;
    }
    String clientIp(HttpServletRequest request) {
        String remote=normalize(request.getRemoteAddr());
        // Only known reverse proxies may supply forwarded identities. Walk from the trusted end.
        if(proxies.contains(remote)) {
            String forwarded=request.getHeader("X-Forwarded-For");
            if(forwarded!=null&&!forwarded.isBlank()) {
                String[] chain=forwarded.split(",");
                for(int i=chain.length-1;i>=0;i--){String candidate=normalize(chain[i]);if(!proxies.contains(candidate))return candidate;}
            }
            String real=request.getHeader("X-Real-IP");if(real!=null&&!real.isBlank())return normalize(real);
        }
        return remote;
    }
    private static String normalize(String value){return value==null?"":value.trim().replaceFirst("^::ffff:","");}
    static boolean publicIp(String value) {
        if(value==null||!value.matches("[0-9a-fA-F:.]+"))return false;
        try {
            var ip=InetAddress.getByName(value);byte[] b=ip.getAddress();
            if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress())return false;
            if(b.length==16)return (b[0]&0xfe)!=0xfc && (b[0]&0xe0)==0x20;
            int a=b[0]&255,c=b[1]&255;
            return a!=0&&a<224&&!(a==100&&c>=64&&c<=127)&&!(a==198&&(c==18||c==19));
        }catch(Exception ignored){return false;}
    }
}
