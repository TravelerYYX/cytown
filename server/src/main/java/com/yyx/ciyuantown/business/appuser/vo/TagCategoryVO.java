package com.yyx.ciyuantown.business.appuser.vo;
import java.util.List;
/** 标签库按分类分组视图。 */
public record TagCategoryVO(String category, List<TagItemVO> tags) {
    public record TagItemVO(Long id, String name) {}
}
