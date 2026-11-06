package com.yyx.ciyuantown.business.sys.dto;

import com.yyx.ciyuantown.common.exception.BizException;
import lombok.Data;

/**
 * 官方账号列表查询参数
 */
@Data
public class AdminUserQueryParam {

    private Integer pageNum = 1;

    private Integer pageSize = 10;

    /** 关键词：匹配 username / nickname */
    private String keyword;

    /** 状态筛选：null=全部，0正常 1禁用 */
    private Integer status;

    public void validate() {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 100)
            throw new BizException("页码不能小于1，每页条数必须为1到100");
        if (keyword != null && keyword.length() > 100) throw new BizException("关键词不能超过100字");
    }

    public long offset() {
        return ((long) pageNum - 1) * pageSize;
    }
}
