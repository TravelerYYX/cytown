package com.yyx.ciyuantown.business.mineentry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yyx.ciyuantown.business.mineentry.mapper.MineEntryMapper;
import com.yyx.ciyuantown.business.homeconfig.EntryTarget;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MineEntryService {
    private static final String HISTORY_ICON = "/uploads/browse-history/mine-history.svg";
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
    private final MineEntryMapper mapper;
    private final StorageService storage;

    public List<MineEntry> list(boolean enabledOnly) {
        var query = new LambdaQueryWrapper<MineEntry>();
        if (enabledOnly) query.eq(MineEntry::getEnabled, true);
        return mapper.selectList(query.orderByAsc(MineEntry::getSortOrder, MineEntry::getId));
    }

    @Transactional
    public MineEntry save(MineEntryParam param, boolean create) {
        if(param==null)throw new BizException("入口内容不能为空");
        cleanup.track("mine_entry",create?null:param.getId());
        if (param == null) throw new BizException("入口内容不能为空");
        String title = text(param.getTitle(), 20, "标题");
        String description = text(param.getDescription(), 100, "介绍");
        String icon = param.getIconUrl();
        if (!HISTORY_ICON.equals(icon) && !"/uploads/shop/mine-shop.png".equals(icon) && (icon == null || icon.length() > 2048 || storage.find(icon)
                .filter(object -> "image/svg+xml".equals(object.contentType()) && object.key().startsWith("config/mine-icons/"))
                .isEmpty())) throw new BizException("请上传或粘贴有效的 SVG 图标");
        String targetType = EntryTarget.type(param.getTargetType());
        String link = EntryTarget.validate(targetType, param.getLinkUrl());
        if (param.getOpenMode() == null || !Set.of("inapp", "browser").contains(param.getOpenMode()))
            throw new BizException("打开方式不合法");
        if (param.getShowTopbar() == null || param.getSortOrder() == null || param.getEnabled() == null)
            throw new BizException("顶部栏、排序及启用状态不能为空");
        if (!create) require(param.getId());
        MineEntry row = new MineEntry();
        BeanUtils.copyProperties(param, row);
        row.setTitle(title); row.setDescription(description); row.setLinkUrl(link); row.setTargetType(targetType);
        if ("app".equals(targetType)) { row.setOpenMode("inapp"); row.setShowTopbar(true); }
        if (create) { row.setId(null); mapper.insert(row); }
        else if (mapper.updateById(row) != 1) throw new BizException("入口不存在");
        return mapper.selectById(row.getId());
    }

    @Transactional
    public void delete(Long id) { cleanup.track("mine_entry",id); require(id); mapper.deleteById(id); }

    @Transactional
    public void sort(List<Long> ids) {
        List<Long> existing = list(false).stream().map(MineEntry::getId).toList();
        if (ids == null || ids.stream().anyMatch(Objects::isNull) || ids.size() != new HashSet<>(ids).size()
                || !new HashSet<>(ids).equals(new HashSet<>(existing)))
            throw new BizException("入口列表已变化，请刷新后重新排序");
        for (int i = 0; i < ids.size(); i++) mapper.update(null, new LambdaUpdateWrapper<MineEntry>()
                .eq(MineEntry::getId, ids.get(i)).set(MineEntry::getSortOrder, i));
    }

    private void require(Long id) {
        if (id == null || mapper.selectById(id) == null) throw new BizException("入口不存在");
    }
    private String text(String value, int max, String label) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isBlank() || trimmed.codePointCount(0, trimmed.length()) > max)
            throw new BizException(label + "需为1-" + max + "个字符");
        return trimmed;
    }
}
