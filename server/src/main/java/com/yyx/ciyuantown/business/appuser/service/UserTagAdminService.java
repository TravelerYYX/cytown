package com.yyx.ciyuantown.business.appuser.service;
import com.yyx.ciyuantown.business.appuser.dto.UserTagCreateParam;
import com.yyx.ciyuantown.business.appuser.dto.UserTagUpdateParam;
import com.yyx.ciyuantown.business.appuser.vo.UserTagAdminVO;
import java.util.List;
public interface UserTagAdminService {
    List<UserTagAdminVO> list();
    UserTagAdminVO create(UserTagCreateParam param);
    UserTagAdminVO update(UserTagUpdateParam param);
    void delete(Long id);
}
