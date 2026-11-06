package com.yyx.ciyuantown.common.redis;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service @RequiredArgsConstructor
public class RedisHotspotService {
 public record FeedPage(List<Long> ids,long total){}
 private static final DefaultRedisScript<Long> RELATION_SCRIPT=new DefaultRedisScript<>("if ARGV[3]=='1' then redis.call('SADD',KEYS[1],ARGV[1]);redis.call('SADD',KEYS[2],ARGV[2]) else redis.call('SREM',KEYS[1],ARGV[1]);redis.call('SREM',KEYS[2],ARGV[2]) end;redis.call('SET',KEYS[3],ARGV[4]);return 1",Long.class);
 private static final DefaultRedisScript<Long> SINGLE_RELATION_SCRIPT=new DefaultRedisScript<>("if ARGV[2]=='1' then redis.call('SADD',KEYS[1],ARGV[1]) else redis.call('SREM',KEYS[1],ARGV[1]) end;redis.call('SET',KEYS[2],ARGV[3]);return 1",Long.class);
 private static final DefaultRedisScript<Long> LIMIT_SCRIPT=new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end;return n",Long.class);
 private static final DefaultRedisScript<Long> UNLOCK_SCRIPT=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end;return 0",Long.class);
 private final ObjectProvider<StringRedisTemplate> templates;
 private final Map<String,String> localLocks=new ConcurrentHashMap<>();
 @Value("${ciyuantown.redis.enabled:true}") private boolean enabled;
 private StringRedisTemplate redis(){return enabled?templates.getIfAvailable():null;}
 public Optional<Boolean> member(String key,String value){try{var r=redis();return r==null?Optional.empty():Optional.of(Boolean.TRUE.equals(r.opsForSet().isMember(key,value)));}catch(Exception e){return Optional.empty();}}
 public Optional<Integer> integer(String key){try{var r=redis();String value=r==null?null:r.opsForValue().get(key);return value==null?Optional.empty():Optional.of(Integer.parseInt(value));}catch(Exception e){return Optional.empty();}}
 public Optional<String> string(String key){try{var r=redis();String value=r==null?null:r.opsForValue().get(key);return Optional.ofNullable(value);}catch(Exception e){return Optional.empty();}}
 public void put(String key,String value,Duration ttl){try{var r=redis();if(r!=null)r.opsForValue().set(key,value,ttl);}catch(Exception ignored){}}
 public void relation(String setKey,String reverseKey,String member,String reverseMember,boolean active,String countKey,int count){try{var r=redis();if(r!=null)r.execute(RELATION_SCRIPT,List.of(setKey,reverseKey,countKey),member,reverseMember,active?"1":"0",String.valueOf(Math.max(0,count)));}catch(Exception ignored){}}
 public void cacheRelation(String setKey,String member,boolean active,String countKey,int count){try{var r=redis();if(r!=null)r.execute(SINGLE_RELATION_SCRIPT,List.of(setKey,countKey),member,active?"1":"0",String.valueOf(Math.max(0,count)));}catch(Exception ignored){}}
 public boolean allow(String action,String subject,int max,Duration window){try{var r=redis();if(r==null)return true;Long value=r.execute(LIMIT_SCRIPT,List.of("ct:rl:"+action+":"+subject),String.valueOf(window.toMillis()));return value==null||value<=max;}catch(Exception e){return true;}}
 public Optional<String> tryLock(String key,Duration ttl){String token=UUID.randomUUID().toString();try{var r=redis();if(r!=null)return Boolean.TRUE.equals(r.opsForValue().setIfAbsent(key,token,ttl))?Optional.of(token):Optional.empty();}catch(Exception ignored){}return localLocks.putIfAbsent(key,token)==null?Optional.of("local:"+token):Optional.empty();}
 public void unlock(String key,String token){if(token.startsWith("local:")){localLocks.remove(key,token.substring(6));return;}try{var r=redis();if(r!=null)r.execute(UNLOCK_SCRIPT,List.of(key),token);}catch(Exception ignored){}}
 public void evict(String...keys){try{var r=redis();if(r!=null)r.delete(java.util.List.of(keys));}catch(Exception ignored){}}
 public void feed(String key,long score,long postId,boolean add){try{var r=redis();if(r==null)return;if(add)r.opsForZSet().add(key,String.valueOf(postId),score);else r.opsForZSet().remove(key,String.valueOf(postId));}catch(Exception ignored){}}
 public Optional<FeedPage> feedPage(String key,long offset,int size){try{var r=redis();if(r==null||!Boolean.TRUE.equals(r.hasKey("ct:feed:ready")))return Optional.empty();Long total=r.opsForZSet().zCard(key);if(total==null||total==0)return Optional.empty();var values=r.opsForZSet().reverseRange(key,offset,offset+size-1);if(values==null)return Optional.empty();List<Long> ids=values.stream().map(Long::valueOf).toList();return Optional.of(new FeedPage(ids,total));}catch(Exception e){return Optional.empty();}}
}
