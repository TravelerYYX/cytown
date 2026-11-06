package com.yyx.ciyuantown.business.post.dto;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.Data;
import java.util.Set;

@Data
public class CommentQueryParam {
    private Integer pageNum = 1;
    private Integer pageSize = 20;
    private String scope = "all";
    private String sort = "hot";
    public void validate() {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 100)
            throw new BizException("页码不能小于1，每页条数必须为1到100");
        if (!Set.of("all", "author").contains(scope)) throw new BizException("评论筛选方式不合法");
        if (!Set.of("hot", "latest").contains(sort)) throw new BizException("评论排序方式不合法");
    }
    public long offset() { return ((long) pageNum - 1) * pageSize; }
}
