package com.yyx.ciyuantown.business.auth.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.auth.service.AuthService;
import com.yyx.ciyuantown.business.auth.vo.LoginResult;
import com.yyx.ciyuantown.business.auth.vo.UserInfoVO;
import com.yyx.ciyuantown.business.sys.entity.AdminUser;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import com.yyx.ciyuantown.common.api.ResultCode;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 认证服务实现
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;

    /** 静态路由模式下前端约定的超管角色 */
    private static final String ROLE_SUPER = "R_SUPER";

    /** 官方只读账号角色（前端 filterAuthRoutesByRoles 据此过滤超管专属页面） */
    private static final String ROLE_OFFICIAL = "R_OFFICIAL";

    /** 头像访问的 URL 前缀与磁盘子目录 */
    private static final String AVATAR_URL_PREFIX = "/uploads/avatar/";

    /** 允许上传的头像扩展名 */
    private static final Set<String> AVATAR_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    /** 头像大小上限：2MB */
    private static final long AVATAR_MAX_SIZE = 2 * 1024 * 1024;

    private final com.yyx.ciyuantown.business.appuser.service.LoginAccountService accounts;
    private final AdminUserMapper adminUserMapper;

    private final JwtUtil jwtUtil;

    /** 上传文件根目录 */
    @Value("${ciyuantown.upload-dir:./uploads}")
    private String uploadDir;
    private final com.yyx.ciyuantown.common.storage.StorageService storage;

    @Override
    public LoginResult login(String userName, String password) {
        AdminUser user = adminUserMapper.selectOne(
                new LambdaQueryWrapper<AdminUser>().apply("active_username={0}",userName));
        if (user == null || user.hasBeenDeleted() || !BCrypt.checkpw(password, user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 1) {
            throw new BizException("账号已被禁用");
        }
        return new LoginResult(accessToken(user), refreshToken(user));
    }

    @Override
    public LoginResult refreshToken(String refreshToken) {
        Long userId = jwtUtil.verifyAndGetUserId(refreshToken, "refresh");
        if (userId == null) {
            // refreshToken 也失效：返回 8888 让前端登出并跳转登录页
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        AdminUser user = adminUserMapper.selectById(userId);
        // 与 APP 端对称：校验状态与 token 版本，防止已禁用/被踢的账号用旧 refreshToken 换新 token
        if (user == null || user.hasBeenDeleted() || (user.getStatus() != null && user.getStatus() == 1)
                || !java.util.Objects.equals(user.getTokenVersion() == null ? 0 : user.getTokenVersion(),
                        jwtUtil.tokenVersion(refreshToken))) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return new LoginResult(accessToken(user), refreshToken(user));
    }

    @Override
    public UserInfoVO getUserInfo(Long userId) {
        AdminUser user = adminUserMapper.selectById(userId);
        if (user == null || user.hasBeenDeleted()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(String.valueOf(user.getId()));
        vo.setUserName(user.getUsername());
        vo.setNickName(user.getNickname());
        vo.setAvatar(user.getAvatar());
        boolean official = user.getUserType() != null && user.getUserType() == 1;
        vo.setRoles(List.of(official ? ROLE_OFFICIAL : ROLE_SUPER));
        vo.setButtons(List.of());
        vo.setUserType(official ? 1 : 0);
        return vo;
    }

    private AdminUser requireActive(Long id) {
        var user = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getId,id).last("FOR UPDATE"));
        if (user == null || user.hasBeenDeleted() || !Integer.valueOf(0).equals(user.getStatus())) throw new BizException(ResultCode.UNAUTHORIZED);
        return user;
    }

    /** 签发 access token：携带 token 版本与用户类型（ut），refresh 时透传防止只读账号升级为超管 */
    private String accessToken(AdminUser user) {
        return jwtUtil.createAccessToken(user.getId(), "admin",
                user.getTokenVersion() == null ? 0 : user.getTokenVersion(),
                user.getUserType() == null ? 0 : user.getUserType());
    }

    private String refreshToken(AdminUser user) {
        return jwtUtil.createRefreshToken(user.getId(), "admin",
                user.getTokenVersion() == null ? 0 : user.getTokenVersion(),
                user.getUserType() == null ? 0 : user.getUserType());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void updateUserName(Long userId, String newUserName) {
        accounts.requireSuper(userId);
        adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getId,userId).last("FOR UPDATE"));
        newUserName = newUserName.trim();
        accounts.rename(accounts.ADMIN,userId,newUserName);
        Long count = adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                .apply("active_username={0}",newUserName)
                .ne(AdminUser::getId, userId));
        if (count != null && count > 0) {
            throw new BizException("用户名已存在");
        }
        AdminUser update = new AdminUser();
        update.setId(userId);
        update.setUsername(newUserName);
        adminUserMapper.updateById(update);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void updateNickname(Long userId, String nickname) {
        requireActive(userId);
        AdminUser update = new AdminUser();
        update.setId(userId);
        update.setNickname(nickname);
        adminUserMapper.updateById(update);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public String updateAvatar(Long userId, MultipartFile file) {
        cleanup.track("admin_user",userId);
        AdminUser user = requireActive(userId);
        if (user == null || user.hasBeenDeleted()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件不能为空");
        }
        if (file.getSize() > AVATAR_MAX_SIZE) {
            throw new BizException("头像大小不能超过 2MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BizException("仅支持上传图片文件");
        }
        String originalFilename = file.getOriginalFilename();
        String extension = getExtension(originalFilename);
        if (!AVATAR_EXTENSIONS.contains(extension)) {
            throw new BizException("仅支持 jpg/jpeg/png/gif/webp 格式的图片");
        }

        String avatarUrl=storage.uploadFile("avatar",file);
        AdminUser update=new AdminUser();update.setId(userId);update.setAvatar(avatarUrl);
        try{adminUserMapper.updateById(update);}catch(RuntimeException e){storage.delete(avatarUrl);throw e;}
        return avatarUrl;
    }

    /** 从文件名提取小写扩展名（不含点，无扩展名返回空串） */
    private String getExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    /** 删除旧头像文件（仅在位于 avatar 目录内时，防止路径被篡改误删其他文件） */
    private void deleteOldAvatarFile(String oldAvatarUrl, Path avatarDir) {
        if (oldAvatarUrl == null || !oldAvatarUrl.startsWith(AVATAR_URL_PREFIX)) {
            return;
        }
        Path oldFile = avatarDir.resolve(oldAvatarUrl.substring(AVATAR_URL_PREFIX.length())).normalize();
        if (oldFile.startsWith(avatarDir)) {
            try {
                Files.deleteIfExists(oldFile);
            } catch (IOException ignored) {
                // 旧文件删除失败不影响主流程
            }
        }
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void updatePassword(Long userId, String oldPassword, String newPassword) {
        AdminUser user = requireActive(userId);
        if (user == null || user.hasBeenDeleted()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        if (!BCrypt.checkpw(oldPassword, user.getPassword())) {
            throw new BizException("旧密码错误");
        }
        AdminUser update = new AdminUser();
        update.setId(userId);
        update.setPassword(BCrypt.hashpw(newPassword, BCrypt.gensalt()));
        adminUserMapper.updateById(update);
    }
}
