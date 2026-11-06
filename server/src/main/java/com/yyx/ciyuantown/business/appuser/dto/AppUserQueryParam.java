package com.yyx.ciyuantown.business.appuser.dto;
import lombok.Data;
import com.yyx.ciyuantown.common.exception.BizException;
@Data
public class AppUserQueryParam {
    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private String keyword;
    public void validate() {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 100)
            throw new BizException("页码不能小于1，每页条数必须为1到100");
        if (keyword != null && keyword.length() > 100) throw new BizException("关键词不能超过100字");
    }
    public long offset() { return ((long) pageNum - 1) * pageSize; }
}
