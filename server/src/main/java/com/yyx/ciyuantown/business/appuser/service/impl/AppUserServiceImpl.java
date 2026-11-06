package com.yyx.ciyuantown.business.appuser.service.impl;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.appuser.dto.AppUserCreateParam;
import com.yyx.ciyuantown.business.appuser.dto.AppUserQueryParam;
import com.yyx.ciyuantown.business.appuser.dto.UpdateTagsParam;
import com.yyx.ciyuantown.business.appuser.entity.*;
import com.yyx.ciyuantown.business.appuser.mapper.*;
import com.yyx.ciyuantown.business.appuser.service.AppUserService;
import com.yyx.ciyuantown.business.appuser.vo.*;
import com.yyx.ciyuantown.business.sys.entity.AdminUser;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import com.yyx.ciyuantown.common.api.ResultCode;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.util.Passwords;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import java.time.Duration;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppUserServiceImpl implements AppUserService {
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
    private final com.yyx.ciyuantown.business.appuser.service.LoginAccountService accounts;
    private final AppUserMapper users;
    private final AdminUserMapper adminUsers;
    private final AppSystemAvatarMapper systemAvatars;
    private final AppTagMapper tags;
    private final AppUserTagMapper userTags;
    private final RedisHotspotService redis;
    private final ObjectMapper objectMapper;
    private final com.yyx.ciyuantown.business.checkin.CheckinService checkins;
    @Value("${ciyuantown.upload-dir:./uploads}") private String uploadDir;
    private final com.yyx.ciyuantown.common.storage.StorageService storage;
    private static final String PREFIX = "/uploads/app-avatar/";
    private static final Set<String> EXTENSIONS = Set.of("jpg","jpeg","png","gif","webp");
    private static final Duration PROFILE_TTL = Duration.ofMinutes(30);
    private static final Duration META_TTL = Duration.ofHours(1);
    private static final int MAX_TAGS = 10;
    // 卡片背景只存前端静态资源文件名（如 01.png），服务端不保存图片本体。
    private static final java.util.regex.Pattern CARD_BACKGROUND_PATTERN = java.util.regex.Pattern.compile("^[0-9]{2}\\.(png|jpg|jpeg|webp)$");
    private static String profileKey(Long id) { return "ct:user:profile:"+id; }

    private AppUser require(Long id, boolean lock) {
        var user = lock ? users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,id).last("FOR UPDATE"))
                        : users.selectById(id);
        if (user == null || user.hasBeenDeleted() || user.getStatus() != 0) throw new BizException(ResultCode.UNAUTHORIZED);
        return user;
    }

    /** 组装完整资料视图（含标签），供 me 与所有 update 方法复用。 */
    private AppUserInfoVO buildProfile(AppUser user) {
        var relations = userTags.selectList(new LambdaQueryWrapper<AppUserTag>()
                .eq(AppUserTag::getUserId,user.getId()).orderByAsc(AppUserTag::getSortOrder,AppUserTag::getId));
        if (relations.isEmpty()) return accounts.info(user,List.of());
        var systemIds = relations.stream().map(AppUserTag::getTagId).filter(Objects::nonNull).toList();
        Map<Long,String> names = systemIds.isEmpty() ? Map.of()
                : tags.selectBatchIds(systemIds).stream().collect(Collectors.toMap(AppTag::getId,AppTag::getName));
        var tagVOs = relations.stream().map(r -> r.getTagId() == null
                ? new UserTagVO(null,r.getCustomName(),true)
                : new UserTagVO(r.getTagId(),names.getOrDefault(r.getTagId(),""),false)).toList();
        return accounts.info(user,tagVOs);
    }

    private void evictProfileAfterCommit(Long id) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { redis.evict(profileKey(id)); }
        });
    }

    @Override
    public AppUserInfoVO me(Long id) {
        var user = require(id,false);
        var cached = redis.string(profileKey(id));
        if (cached.isPresent()) {
            try {
                var profile = objectMapper.readValue(cached.get(),AppUserInfoVO.class);
                if (profile.account()!=null && profile.isOfficial()!=null && Objects.equals(profile.avatarFrame(),user.getAvatarFrame()==null?"":user.getAvatarFrame()) && profile.coinBalance()!=null && Objects.equals(profile.level(),user.getLevel()) && user.getCoinBalance()!=null && profile.coinBalance().compareTo(user.getCoinBalance())==0 && Objects.equals(profile.avatarFrameExpiresAt(),user.getAvatarFrameExpiresAt())) return profile;
            } catch (Exception ignored) { /* 缓存损坏则回源 */ }
        }
        var profile = buildProfile(user);
        try { redis.put(profileKey(id),objectMapper.writeValueAsString(profile),PROFILE_TTL); } catch (Exception ignored) { /* 缓存失败不影响读取 */ }
        return profile;
    }

    @Override @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public AppUserInfoVO updateNickname(Long id, String nickname) {
        if (nickname == null) throw new BizException("昵称需为2-20个字符");
        nickname = nickname.trim();
        int length = nickname.codePointCount(0,nickname.length());
        if (length < 2 || length > 20) throw new BizException("昵称需为2-20个字符");
        var user = require(id,true); user.setNickname(nickname); users.updateById(user);
        evictProfileAfterCommit(id);
        return buildProfile(user);
    }

    @Override @Transactional
    public AppUserInfoVO updateGender(Long id, Integer gender) {
        if (gender == null || (gender != 0 && gender != 1 && gender != 2)) throw new BizException("性别取值非法");
        var user = require(id,true); user.setGender(gender); users.updateById(user);
        evictProfileAfterCommit(id);
        return buildProfile(user);
    }

    @Override @Transactional
    public AppUserInfoVO updateSignature(Long id, String signature) {
        signature = signature == null ? "" : signature.trim();
        if (signature.codePointCount(0,signature.length()) > 50) throw new BizException("个性签名不能超过50字");
        var user = require(id,true); user.setSignature(signature); users.updateById(user);
        evictProfileAfterCommit(id);
        return buildProfile(user);
    }

    @Override @Transactional
    public AppUserInfoVO updateCardBackground(Long id, String background) {
        background = background == null ? "" : background.trim().toLowerCase(Locale.ROOT);
        if (!CARD_BACKGROUND_PATTERN.matcher(background).matches()) throw new BizException("卡片背景不存在");
        var user = require(id,true); user.setCardBackground(background); users.updateById(user);
        evictProfileAfterCommit(id);
        return buildProfile(user);
    }

    @Override @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public AppUserInfoVO updateAvatarFrame(Long id, String frameId) {
        if(frameId==null)throw new BizException("头像框不存在");
        var user = require(id,true);
        Long expiry=checkins.requireFrame(id,frameId);
        user.setAvatarFrame(frameId);
        user.setAvatarFrameExpiresAt(expiry);
        users.updateById(user);
        // MyBatis defaults to ignoring NULL fields; explicitly clear a former timed selection.
        users.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AppUser>().eq(AppUser::getId,id).set(AppUser::getAvatarFrameExpiresAt,expiry));
        evictProfileAfterCommit(id);
        return buildProfile(user);
    }

    @Override @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public AppUserInfoVO updateAvatar(Long id, MultipartFile file) {
        cleanup.track("app_user",id);
        var user = require(id,true);
        if (file == null || file.isEmpty()) throw new BizException("上传文件不能为空");
        if (file.getSize() > 2 * 1024 * 1024) throw new BizException("头像大小不能超过 2MB");
        String contentType = file.getContentType(), original = file.getOriginalFilename();
        String ext = original == null || !original.contains(".") ? "" : original.substring(original.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if (contentType == null || !contentType.startsWith("image/") || !EXTENSIONS.contains(ext))
            throw new BizException("仅支持 jpg/jpeg/png/gif/webp 格式的图片");
        String newUrl = storage.uploadFile("app-avatar",file);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status != STATUS_COMMITTED){storage.delete(newUrl);return;}
            }
        });
        user.setAvatar(newUrl);users.updateById(user);
        evictProfileAfterCommit(id);
        return buildProfile(user);
    }

    @Override @Transactional
    @CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public AppUserInfoVO selectSystemAvatar(Long id, Long avatarId) {
        cleanup.track("app_user",id);
        var user = require(id,true);
        var avatar = avatarId == null ? null : systemAvatars.selectById(avatarId);
        if (avatar == null || avatar.getStatus() != 0) throw new BizException("头像不存在或已下架");
        user.setAvatar(avatar.getUrl()); users.updateById(user);
        evictProfileAfterCommit(id);
        return buildProfile(user);
    }

    @Override
    public SystemAvatarGroupVO systemAvatars() {
        var cached = redis.string("ct:meta:avatars");
        if (cached.isPresent()) {
            try { return objectMapper.readValue(cached.get(),SystemAvatarGroupVO.class); } catch (Exception ignored) { /* 回源 */ }
        }
        var all = systemAvatars.selectList(new LambdaQueryWrapper<AppSystemAvatar>()
                .eq(AppSystemAvatar::getStatus,0).orderByAsc(AppSystemAvatar::getSort,AppSystemAvatar::getId));
        var group = new SystemAvatarGroupVO(
                all.stream().filter(a -> a.getType() == 1).map(a -> new SystemAvatarGroupVO.SystemAvatarItemVO(a.getId(),a.getUrl())).toList(),
                all.stream().filter(a -> a.getType() == 0).map(a -> new SystemAvatarGroupVO.SystemAvatarItemVO(a.getId(),a.getUrl())).toList());
        try { redis.put("ct:meta:avatars",objectMapper.writeValueAsString(group),META_TTL); } catch (Exception ignored) { }
        return group;
    }

    @Override
    public List<TagCategoryVO> tagLibrary() {
        var cached = redis.string("ct:meta:tags");
        if (cached.isPresent()) {
            try {
                var type = objectMapper.getTypeFactory().constructCollectionType(List.class,TagCategoryVO.class);
                return objectMapper.readValue(cached.get(),type);
            } catch (Exception ignored) { /* 回源 */ }
        }
        // 按录入顺序返回，LinkedHashMap 保持分类首次出现顺序（游戏/本体/兴趣/MBTI），同分类内再按 sort。
        var all = tags.selectList(new LambdaQueryWrapper<AppTag>()
                .eq(AppTag::getStatus,0).orderByAsc(AppTag::getId));
        all.sort(Comparator.comparing(AppTag::getSort).thenComparing(AppTag::getId));
        Map<String,List<TagCategoryVO.TagItemVO>> grouped = new LinkedHashMap<>();
        for (var tag : all) grouped.computeIfAbsent(tag.getCategory(),k -> new ArrayList<>())
                .add(new TagCategoryVO.TagItemVO(tag.getId(),tag.getName()));
        var library = grouped.entrySet().stream().map(e -> new TagCategoryVO(e.getKey(),e.getValue())).toList();
        try { redis.put("ct:meta:tags",objectMapper.writeValueAsString(library),META_TTL); } catch (Exception ignored) { }
        return library;
    }

    @Override @Transactional
    public AppUserInfoVO updateTags(Long id, List<UpdateTagsParam.TagItem> items) {
        var user = require(id,true);
        items = items == null ? List.of() : items;
        if (items.size() > MAX_TAGS) throw new BizException("最多选择10个标签");
        // 校验并解析每个标签：系统标签须存在且启用；自定义标签名 trim 后 1-20 字；整体按展示名判重（忽略大小写）。
        var systemIds = items.stream().map(UpdateTagsParam.TagItem::tagId).filter(Objects::nonNull).toList();
        Map<Long,AppTag> systemTagMap = systemIds.isEmpty() ? Map.of()
                : tags.selectBatchIds(systemIds).stream().collect(Collectors.toMap(AppTag::getId,t -> t));
        Set<String> seen = new HashSet<>();
        var relations = new ArrayList<AppUserTag>();
        int order = 0;
        for (var item : items) {
            var relation = new AppUserTag();
            relation.setUserId(id); relation.setSortOrder(order++);
            String displayName;
            if (item.tagId() != null) {
                var tag = systemTagMap.get(item.tagId());
                if (tag == null || tag.getStatus() != 0) throw new BizException("标签不存在或已下架");
                relation.setTagId(item.tagId());
                displayName = tag.getName();
            } else {
                String name = item.customName() == null ? "" : item.customName().trim();
                int length = name.codePointCount(0,name.length());
                if (length < 1 || length > 20) throw new BizException("标签名需为1-20个字符");
                relation.setCustomName(name);
                displayName = name;
            }
            if (!seen.add(displayName.toLowerCase(Locale.ROOT))) throw new BizException("标签已存在");
            relations.add(relation);
        }
        userTags.delete(new LambdaQueryWrapper<AppUserTag>().eq(AppUserTag::getUserId,id));
        relations.forEach(userTags::insert);
        evictProfileAfterCommit(id);
        return buildProfile(user);
    }

    private static void delete(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { /* 清理失败不覆盖业务结果。 */ }
    }
    @Override @Transactional
    public AppUserManageVO createByAdmin(AppUserCreateParam param) {
        String qq = param.qqNumber().trim();
        if (users.selectCount(new LambdaQueryWrapper<AppUser>().apply("active_qq_number={0}",qq)) > 0) throw new BizException("该QQ号已注册");
        String account = param.account()==null||param.account().isBlank()?null:accounts.validate(param.account());
        boolean withPassword = param.password() != null && !param.password().isBlank();
        if (withPassword) Passwords.validate(param.password());
        var user = new AppUser();
        user.setQqNumber(qq); user.setEmail(qq+"@qq.com"); user.setUsername(account);
        user.setNickname(param.nickname()==null||param.nickname().isBlank() ? "町酱"+qq : param.nickname().trim());
        user.setAvatar(""); user.setLevel(1); user.setStatus(0);
        users.insert(user);
        accounts.reserve(accounts.APP,user.getId(),account==null?qq:account);
        // insert 后回填应用层默认值（与验证码注册一致）；createTime 等库默认值不在回填范围，靠回查取回
        user.setPublicUid(user.getId()+999L);
        user.setTokenVersion(0);
        if (withPassword) user.setPassword(BCrypt.hashpw(param.password(),BCrypt.gensalt()));
        users.updateById(user);
        // 回查再转 VO：insert 不回填 DB 默认值列，直接 from(user) 会因 createTime 为 null 报错
        return AppUserManageVO.from(users.selectById(user.getId()),accounts.account(user));
    }

    @Override @Transactional(readOnly=true)
    public Page<AppUserManageVO> list(AppUserQueryParam q) {
        q.validate();
        var filter = new LambdaQueryWrapper<AppUser>().eq(AppUser::getDeleted,false);
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            String keyword = q.getKeyword().trim();
            var matches = accounts.officialIdsMatching(keyword);
            filter.and(w -> {
                w.like(AppUser::getQqNumber,keyword).or().like(AppUser::getUsername,keyword).or().like(AppUser::getNickname,keyword);
                if (keyword.matches("[0-9]{1,18}")) w.or().eq(AppUser::getPublicUid,Long.parseLong(keyword)).or().eq(AppUser::getId,Long.parseLong(keyword));
                if (!matches.isEmpty()) w.or().in(AppUser::getOfficialAdminId,matches);
            });
        }
        long count = users.selectCount(filter);
        var page = new Page<AppUserManageVO>(q.getPageNum(),q.getPageSize(),count);
        if (q.offset() >= count) return page.setRecords(List.of());
        filter.orderByDesc(AppUser::getCreateTime,AppUser::getId).last("LIMIT "+q.offset()+","+q.getPageSize());
        return page.setRecords(users.selectList(filter).stream().map(user -> AppUserManageVO.from(user,accounts.account(user))).toList());
    }
    @Override @Transactional
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) throw new BizException("状态仅允许0、1");
        var user = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,id).last("FOR UPDATE"));
        if (user == null || user.hasBeenDeleted()) throw new BizException("用户不存在");
        int next=(user.getTokenVersion()==null?0:user.getTokenVersion())+1;var update = new AppUser(); update.setId(id); update.setStatus(status); update.setTokenVersion(next); users.updateById(update);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){redis.put("ct:session:ver:"+id,String.valueOf(next),Duration.ofMinutes(10));redis.evict(profileKey(id));}});
    }

    @Override @Transactional
    public void setPassword(Long id, String password) {
        var user = require(id,true);
        rejectOfficialPasswordChange(user);
        if (user.getPassword() != null && !user.getPassword().isBlank()) throw new BizException("密码已设置，请使用修改密码");
        Passwords.validate(password);
        applyPassword(user,password);
    }

    @Override @Transactional
    public void updatePassword(Long id, String oldPassword, String newPassword) {
        var user = require(id,true);
        rejectOfficialPasswordChange(user);
        if (user.getPassword() == null || user.getPassword().isBlank()) throw new BizException("尚未设置密码");
        if (!BCrypt.checkpw(oldPassword,user.getPassword())) throw new BizException("旧密码错误");
        if (oldPassword.equals(newPassword)) throw new BizException("新密码不能与旧密码相同");
        Passwords.validate(newPassword);
        applyPassword(user,newPassword);
    }

    /**
     * 官方账号的社区身份（official_admin_id 固定关联）密码由管理后台统一维护：
     * 密码登录校验的是 admin_user.password，APP 内改密码不会生效，因此直接拦截避免误导。
     */
    private void rejectOfficialPasswordChange(AppUser user) {
        if (user.getOfficialAdminId()!=null) throw new BizException("官方账号密码请在管理后台重置");
    }

    /** BCrypt 存储并递增 token 版本，提交后写 Redis 踢下线 */
    private void applyPassword(AppUser user, String rawPassword) {
        int next=(user.getTokenVersion()==null?0:user.getTokenVersion())+1;
        user.setPassword(BCrypt.hashpw(rawPassword,BCrypt.gensalt()));
        user.setTokenVersion(next);
        users.updateById(user);
        Long userId = user.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){redis.put("ct:session:ver:"+userId,String.valueOf(next),Duration.ofMinutes(10));redis.evict(profileKey(userId));}});
    }
}
