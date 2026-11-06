package com.yyx.ciyuantown.business.notice.dto;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.Data;
@Data public class NoticeQueryParam {
 private Integer pageNum=1; private Integer pageSize=10; private Long categoryId; private Integer status;
 public void validate() { if(pageNum==null||pageNum<1||pageSize==null||pageSize<1||pageSize>100) throw new BizException("页码不能小于1，每页条数必须为1到100"); if(status!=null&&status!=0&&status!=1) throw new BizException("状态仅允许0、1"); }
 public long offset(){ return ((long)pageNum-1)*pageSize; }
}
