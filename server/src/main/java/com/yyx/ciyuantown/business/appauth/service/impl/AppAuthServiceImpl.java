package com.yyx.ciyuantown.business.appauth.service.impl;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.appauth.service.*;
import com.yyx.ciyuantown.business.appauth.vo.AppLoginResult;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.appuser.vo.AppUserInfoVO;
import com.yyx.ciyuantown.business.sys.entity.AdminUser;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import com.yyx.ciyuantown.common.api.ResultCode;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.util.JwtUtil;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import org.springframework.transaction.support.*;
@Service
@RequiredArgsConstructor
public class AppAuthServiceImpl implements AppAuthService {
    /** 密码登录失败限流：10 分钟内最多 5 次失败，成功后清零 */
    private static final String PWD_LOGIN_LIMIT = "app-pwd-login";
    private static final int PWD_LOGIN_MAX_FAILS = 5;
    private static final Duration PWD_LOGIN_WINDOW = Duration.ofMinutes(10);
    /** 官方账号用户类型（AdminUser.userType） */
    private static final int TYPE_OFFICIAL = 1;
    private final com.yyx.ciyuantown.business.appuser.service.LoginAccountService accounts;
    private final AppUserMapper users;
    private final AdminUserMapper adminUsers;
    private final EmailCodeService codes;
    private final JwtUtil jwt;
    private final RedisHotspotService redis;
    private final com.yyx.ciyuantown.business.appuser.service.UserActivityService activity;
    @Override
    @Transactional
    public AppLoginResult login(String qqNumber, String code) {
        codes.verify(qqNumber,code);
        var user = users.selectOne(new LambdaQueryWrapper<AppUser>().apply("active_qq_number={0}",qqNumber).last("FOR UPDATE"));
        if (user == null) {
            user = new AppUser();
            user.setQqNumber(qqNumber); user.setEmail(qqNumber+"@qq.com"); user.setNickname("町酱"+qqNumber);
            user.setAvatar(""); user.setLevel(1); user.setStatus(0);
            users.insert(user);
            accounts.reserve(accounts.APP,user.getId(),qqNumber);
            user.setPublicUid(user.getId()+999L);
            user.setTokenVersion(0);
            users.updateById(user);
        }
        if (user.hasBeenDeleted() || user.getStatus() != 0) throw new BizException("账号已被禁用");
        user.setLastLoginTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        users.updateById(user);
        return result(user);
    }
    /**
     * 账号 + 密码登录：由统一登记定位唯一身份，改名后不再接受旧 QQ 密码登录。
     */
    @Override
    @Transactional
    public AppLoginResult loginByPassword(String account, String password) {
        String key = accounts.normalize(account);
        var registered = accounts.find(key);
        if (registered == null) { failLogin(key); return null; }
        if (registered.getOwnerType()==accounts.ADMIN) {
            var admin = adminUsers.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getId,registered.getOwnerId()).last("FOR UPDATE"));
            if (admin==null || admin.hasBeenDeleted() || !java.util.Objects.equals(admin.getUserType(),TYPE_OFFICIAL)
                || !accounts.normalize(admin.getUsername()).equals(key)) { failLogin(key); return null; }
            return loginByOfficialAccount(admin,password);
        }
        var user = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,registered.getOwnerId()).last("FOR UPDATE"));
        if (user == null || user.hasBeenDeleted() || user.getOfficialAdminId()!=null || !accounts.normalize(accounts.account(user)).equals(key)) {
            failLogin(key); return null;
        }
        if (user.hasBeenDeleted() || user.getStatus() != 0) throw new BizException("账号已被禁用");
        if (user.getPassword() == null || user.getPassword().isBlank()) throw new BizException("未设置密码，请使用验证码登录");
        if (!BCrypt.checkpw(password,user.getPassword())) failLogin(key);
        redis.evict(limitKey(key));
        user.setLastLoginTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        users.updateById(user);
        return result(user);
    }
    /**
     * 官方账号登录 APP：凭据以 admin_user 为准（超管在后台创建/重置、禁用），
     * 社区身份 app_user 首次登录时按固定官方账号 ID 惰性建立，之后 APP 接口按普通用户使用。
     * <p>限流与踢下线一律用 {@code admin.getUsername()} 这个规范名做键：MySQL 默认不区分大小写的
     * 排序规则下，大小写变体也会命中同一账号，用规范化名才能让失败计数正确累计。
     */
    private AppLoginResult loginByOfficialAccount(AdminUser admin, String password) {
        if (admin.hasBeenDeleted() || admin.getStatus() == null || admin.getStatus() != 0) throw new BizException("账号已被禁用");
        if (admin.getPassword() == null || !BCrypt.checkpw(password,admin.getPassword())) failLogin(accounts.normalize(admin.getUsername()));
        redis.evict(limitKey(accounts.normalize(admin.getUsername())));
        var user = communityIdentity(admin);
        // 后台禁用官方账号、用户管理里禁用其社区身份，任一禁用都不允许登录
        if (user.hasBeenDeleted() || user.getStatus() != 0) throw new BizException("账号已被禁用");
        user.setLastLoginTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        users.updateById(user);
        return result(user);
    }
    /** 调用者持有 admin 行锁；按固定官方账号 ID 建立唯一社区身份。 */
    private AppUser communityIdentity(AdminUser admin) {
        String username = admin.getUsername();
        var existing = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getOfficialAdminId,admin.getId()).last("FOR UPDATE"));
        if (existing != null) {
            if (existing.hasBeenDeleted()) throw new BizException("账号已被删除");
            return existing;
        }
        var user = new AppUser();
        user.setOfficialAdminId(admin.getId());
        // 官方账号无 QQ，email 是固定 ID 形成的占位值
        user.setEmail("official-"+admin.getId()+"@official.local");
        user.setNickname(admin.getNickname()==null||admin.getNickname().isBlank() ? username : admin.getNickname());
        user.setAvatar(admin.getAvatar()==null ? "" : admin.getAvatar());
        user.setLevel(1); user.setStatus(0);
        users.insert(user);
        user.setPublicUid(user.getId()+999L);
        user.setTokenVersion(0);
        users.updateById(user);
        return user;
    }
    /** 失败计数：allow() 底层为 INCR（每次调用必累加），只能在失败后计数，否则正常登录也会被计入 */
    private void failLogin(String key) {
        if (!redis.allow(PWD_LOGIN_LIMIT,key,PWD_LOGIN_MAX_FAILS,PWD_LOGIN_WINDOW))
            throw new BizException("尝试次数过多，请 10 分钟后再试");
        throw new BizException("账号或密码错误");
    }
    private static String limitKey(String account) { return "ct:rl:"+PWD_LOGIN_LIMIT+":"+account; }
    @Override
    @Transactional
    public AppLoginResult refreshToken(String token) {
        Long id = jwt.verifyAndGetUserId(token,"refresh","app");
        var initial = id == null ? null : users.selectById(id);
        if (initial == null || initial.hasBeenDeleted()) throw new BizException(ResultCode.UNAUTHORIZED);
        if (initial.getOfficialAdminId()!=null) {
            var admin = adminUsers.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getId,initial.getOfficialAdminId()).last("FOR UPDATE"));
            if (admin == null || admin.hasBeenDeleted() || !java.util.Objects.equals(admin.getUserType(),1)
                || !java.util.Objects.equals(admin.getStatus(),0)) throw new BizException(ResultCode.UNAUTHORIZED);
        }
        var user = id == null ? null : users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,id).last("FOR UPDATE"));
        if (user == null || user.hasBeenDeleted() || user.getStatus() != 0 || !java.util.Objects.equals(user.getTokenVersion()==null?0:user.getTokenVersion(),jwt.tokenVersion(token))) throw new BizException(ResultCode.UNAUTHORIZED);
        return result(user);
    }
    @Override @Transactional
    public void logout(Long userId,String token){var user=users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,userId).last("FOR UPDATE"));if(user==null||user.hasBeenDeleted())throw new BizException(ResultCode.UNAUTHORIZED);int next=(user.getTokenVersion()==null?0:user.getTokenVersion())+1;user.setTokenVersion(next);users.updateById(user);String jti=jwt.tokenId(token);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){redis.put("ct:session:ver:"+userId,String.valueOf(next),Duration.ofMinutes(10));if(jti!=null)redis.put("ct:jwt:blacklist:"+jti,"1",Duration.ofHours(2));}});}
    private AppLoginResult result(AppUser user) {
        activity.record(user);
        return new AppLoginResult(jwt.createAccessToken(user.getId(),"app",user.getTokenVersion()),jwt.createRefreshToken(user.getId(),"app",user.getTokenVersion()),
            accounts.info(user,java.util.List.of()));
    }
}
