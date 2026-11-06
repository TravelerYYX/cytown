package com.yyx.ciyuantown.business.appuser.service;
import com.yyx.ciyuantown.business.appuser.dto.SystemAvatarUpdateParam;
import com.yyx.ciyuantown.business.appuser.vo.SystemAvatarAdminVO;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
public interface SystemAvatarService {
    List<SystemAvatarAdminVO> list(Integer type);
    SystemAvatarAdminVO upload(MultipartFile file, Integer type, Integer sort);
    SystemAvatarAdminVO update(SystemAvatarUpdateParam param);
    void delete(Long id);
}
