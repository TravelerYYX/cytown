package com.yyx.ciyuantown.business.appuser.service;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.appuser.dto.AppUserCreateParam;
import com.yyx.ciyuantown.business.appuser.dto.AppUserQueryParam;
import com.yyx.ciyuantown.business.appuser.dto.UpdateTagsParam;
import com.yyx.ciyuantown.business.appuser.vo.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
public interface AppUserService {
    AppUserInfoVO me(Long id);
    AppUserInfoVO updateNickname(Long id, String nickname);
    AppUserInfoVO updateGender(Long id, Integer gender);
    AppUserInfoVO updateSignature(Long id, String signature);
    AppUserInfoVO updateCardBackground(Long id, String background);
    AppUserInfoVO updateAvatarFrame(Long id, String frameId);
    AppUserInfoVO updateAvatar(Long id, MultipartFile file);
    AppUserInfoVO selectSystemAvatar(Long id, Long avatarId);
    SystemAvatarGroupVO systemAvatars();
    List<TagCategoryVO> tagLibrary();
    AppUserInfoVO updateTags(Long id, List<UpdateTagsParam.TagItem> items);
    Page<AppUserManageVO> list(AppUserQueryParam query);
    void updateStatus(Long id, Integer status);
    /** 管理端新建 APP 用户：QQ 号必填唯一，昵称默认「町酱+QQ号」，初始密码可选（留空仅验证码登录） */
    AppUserManageVO createByAdmin(AppUserCreateParam param);
    /** 首次设置登录密码（已设置则拒绝）；成功后递增 token 版本踢下线 */
    void setPassword(Long id, String password);
    /** 修改登录密码（需校验旧密码，且不能与旧密码相同）；成功后递增 token 版本踢下线 */
    void updatePassword(Long id, String oldPassword, String newPassword);
}
