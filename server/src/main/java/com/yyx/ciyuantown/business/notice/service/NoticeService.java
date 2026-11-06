package com.yyx.ciyuantown.business.notice.service;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.notice.dto.*;
import com.yyx.ciyuantown.business.notice.entity.NoticeCategory;
import com.yyx.ciyuantown.business.notice.vo.*;
import java.util.List;
public interface NoticeService {
 List<NoticeCategory> categories(boolean enabledOnly); NoticeCategory saveCategory(NoticeCategoryParam param,boolean create); void deleteCategory(Long id);
 Page<NoticeListVO> list(NoticeQueryParam query,boolean app); NoticeDetailVO detail(Long id,boolean app);
 Long save(NoticeParam param,boolean create); void delete(Long id); void publish(Long id); void unpublish(Long id);
}
