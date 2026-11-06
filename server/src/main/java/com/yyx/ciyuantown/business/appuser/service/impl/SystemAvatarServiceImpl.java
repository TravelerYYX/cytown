package com.yyx.ciyuantown.business.appuser.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.appuser.dto.SystemAvatarUpdateParam;
import com.yyx.ciyuantown.business.appuser.entity.AppSystemAvatar;
import com.yyx.ciyuantown.business.appuser.mapper.AppSystemAvatarMapper;
import com.yyx.ciyuantown.business.appuser.service.SystemAvatarService;
import com.yyx.ciyuantown.business.appuser.vo.SystemAvatarAdminVO;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
@Service
@RequiredArgsConstructor
public class SystemAvatarServiceImpl implements SystemAvatarService {
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
    private final AppSystemAvatarMapper avatars;
    private final RedisHotspotService redis;
    @Value("${ciyuantown.upload-dir:./uploads}") private String uploadDir;
    private final com.yyx.ciyuantown.common.storage.StorageService storage;
    private static final String PREFIX = "/uploads/sys-avatar/";
    private static final Set<String> EXTENSIONS = Set.of("jpg","jpeg","png","gif","webp");
    @Override
    public List<SystemAvatarAdminVO> list(Integer type) {
        var filter = new LambdaQueryWrapper<AppSystemAvatar>();
        if (type != null) filter.eq(AppSystemAvatar::getType,type);
        filter.orderByAsc(AppSystemAvatar::getType,AppSystemAvatar::getSort,AppSystemAvatar::getId);
        return avatars.selectList(filter).stream().map(SystemAvatarAdminVO::from).toList();
    }
    @Override @Transactional
    public SystemAvatarAdminVO upload(MultipartFile file, Integer type, Integer sort) {
        cleanup.lock();
        if (file == null || file.isEmpty()) throw new BizException("上传文件不能为空");
        if (file.getSize() > 10L * 1024 * 1024) throw new BizException("头像大小不能超过 10MB");
        String contentType = file.getContentType(), original = file.getOriginalFilename();
        String ext = original == null || !original.contains(".") ? "" : original.substring(original.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if (contentType == null || !contentType.startsWith("image/") || !EXTENSIONS.contains(ext))
            throw new BizException("仅支持 jpg/jpeg/png/gif/webp 格式的图片");
        int avatarType = type == null ? 0 : type;
        if (avatarType != 0 && avatarType != 1) throw new BizException("头像类型仅支持0静态、1动态");
        String uploaded = storage.uploadFile("sys-avatar",file);
        var avatar = new AppSystemAvatar();
        avatar.setUrl(uploaded); avatar.setType(avatarType);
        avatar.setSort(sort == null ? 0 : sort); avatar.setStatus(0);
        avatars.insert(avatar);
        redis.evict("ct:meta:avatars");
        return SystemAvatarAdminVO.from(avatar);
    }
    @Override @Transactional
    public SystemAvatarAdminVO update(SystemAvatarUpdateParam param) {
        var avatar = avatars.selectById(param.id());
        if (avatar == null) throw new BizException("头像不存在");
        if (param.type() != null) {
            if (param.type() != 0 && param.type() != 1) throw new BizException("头像类型仅支持0静态、1动态");
            avatar.setType(param.type());
        }
        if (param.sort() != null) avatar.setSort(param.sort());
        if (param.status() != null) {
            if (param.status() != 0 && param.status() != 1) throw new BizException("状态仅允许0、1");
            avatar.setStatus(param.status());
        }
        avatars.updateById(avatar);
        redis.evict("ct:meta:avatars");
        return SystemAvatarAdminVO.from(avatar);
    }
    @Override @Transactional
    public void delete(Long id) {
        cleanup.track("app_system_avatar",id);
        var avatar = avatars.selectById(id);
        if (avatar == null) throw new BizException("头像不存在");
        avatars.deleteById(id);
        redis.evict("ct:meta:avatars");
        // Shared references are checked by the cleanup outbox.
    }
    private static void delete(Path path) { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
}
