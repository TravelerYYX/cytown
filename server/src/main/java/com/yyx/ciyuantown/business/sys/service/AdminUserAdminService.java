package com.yyx.ciyuantown.business.sys.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.sys.dto.AdminUserCreateParam;
import com.yyx.ciyuantown.business.sys.dto.AdminUserQueryParam;
import com.yyx.ciyuantown.business.sys.vo.AdminUserVO;

/**
 * 官方账号管理服务（仅超管使用；写操作由 ReadOnlyInterceptor 对只读账号统一拦截）
 */
public interface AdminUserAdminService {

    /** 创建官方账号：username 由管理员填写，密码 BCrypt 存储 */
    AdminUserVO create(Long operatorId, AdminUserCreateParam param);

    /** 官方账号分页列表（仅 user_type=1），返回不含 password */
    Page<AdminUserVO> list(AdminUserQueryParam query);

    /** 重置官方账号密码：新密码 BCrypt 存储，token_version 递增踢下线 */
    void resetPassword(Long operatorId, Long id, String password);

    /** 启用/禁用官方账号：禁用时 token_version 递增，已签发 token 立即失效 */
    void updateStatus(Long operatorId, Long id, Integer status);
}
