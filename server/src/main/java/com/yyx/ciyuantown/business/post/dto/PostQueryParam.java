package com.yyx.ciyuantown.business.post.dto;
import lombok.Data;
import com.yyx.ciyuantown.common.exception.BizException;
@Data
public class PostQueryParam {
    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private Integer status;
    private String keyword;
    private Long channelId;
    public void validate() {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 100)
            throw new BizException("页码不能小于1，每页条数必须为1到100");
        if (status != null && (status < 0 || status > 2)) throw new BizException("状态仅允许0、1、2");
        if (keyword != null && keyword.length() > 100) throw new BizException("关键词不能超过100字");
        if (channelId != null && channelId < 1) throw new BizException("频道ID必须为正数");
    }
    public long offset() { return ((long) pageNum - 1) * pageSize; }
}
