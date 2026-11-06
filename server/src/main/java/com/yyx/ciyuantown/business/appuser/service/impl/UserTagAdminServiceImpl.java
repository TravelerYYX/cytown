package com.yyx.ciyuantown.business.appuser.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.appuser.dto.UserTagCreateParam;
import com.yyx.ciyuantown.business.appuser.dto.UserTagUpdateParam;
import com.yyx.ciyuantown.business.appuser.entity.AppTag;
import com.yyx.ciyuantown.business.appuser.entity.AppUserTag;
import com.yyx.ciyuantown.business.appuser.mapper.AppTagMapper;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserTagMapper;
import com.yyx.ciyuantown.business.appuser.service.UserTagAdminService;
import com.yyx.ciyuantown.business.appuser.vo.UserTagAdminVO;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
@RequiredArgsConstructor
public class UserTagAdminServiceImpl implements UserTagAdminService {
    private final AppTagMapper tags;
    private final AppUserTagMapper userTags;
    private final RedisHotspotService redis;
    @Override
    public List<UserTagAdminVO> list() {
        return tags.selectList(new LambdaQueryWrapper<AppTag>().orderByAsc(AppTag::getId))
                .stream().map(UserTagAdminVO::from).toList();
    }
    @Override @Transactional
    public UserTagAdminVO create(UserTagCreateParam param) {
        String name = param.name().trim();
        if (name.codePointCount(0,name.length()) > 20) throw new BizException("标签名不能超过20个字符");
        String category = param.category().trim();
        if (category.codePointCount(0,category.length()) > 20) throw new BizException("分类名不能超过20个字符");
        var exists = tags.selectOne(new LambdaQueryWrapper<AppTag>()
                .eq(AppTag::getCategory,category).eq(AppTag::getName,name).last("LIMIT 1"));
        if (exists != null) throw new BizException("该分类下标签已存在");
        var tag = new AppTag();
        tag.setName(name); tag.setCategory(category);
        tag.setSource(0); tag.setStatus(param.status() == null ? 0 : (param.status() == 1 ? 1 : 0));
        tag.setSort(param.sort() == null ? 0 : param.sort());
        tags.insert(tag);
        redis.evict("ct:meta:tags");
        return UserTagAdminVO.from(tag);
    }
    @Override @Transactional
    public UserTagAdminVO update(UserTagUpdateParam param) {
        var tag = tags.selectById(param.id());
        if (tag == null) throw new BizException("标签不存在");
        if (param.name() != null) {
            String name = param.name().trim();
            if (name.isEmpty()) throw new BizException("标签名不能为空");
            if (name.codePointCount(0,name.length()) > 20) throw new BizException("标签名不能超过20个字符");
            tag.setName(name);
        }
        if (param.category() != null) {
            String category = param.category().trim();
            if (category.isEmpty()) throw new BizException("分类名不能为空");
            if (category.codePointCount(0,category.length()) > 20) throw new BizException("分类名不能超过20个字符");
            tag.setCategory(category);
        }
        if (param.sort() != null) tag.setSort(param.sort());
        if (param.status() != null) {
            if (param.status() != 0 && param.status() != 1) throw new BizException("状态仅允许0、1");
            tag.setStatus(param.status());
        }
        tags.updateById(tag);
        redis.evict("ct:meta:tags");
        return UserTagAdminVO.from(tag);
    }
    @Override @Transactional
    public void delete(Long id) {
        var tag = tags.selectById(id);
        if (tag == null) throw new BizException("标签不存在");
        // 先清关联，再删标签；同时失效已选过该标签用户的资料缓存。
        var relations = userTags.selectList(new LambdaQueryWrapper<AppUserTag>().eq(AppUserTag::getTagId,id));
        userTags.delete(new LambdaQueryWrapper<AppUserTag>().eq(AppUserTag::getTagId,id));
        tags.deleteById(id);
        List<String> keys = new ArrayList<>(relations.stream().map(r -> "ct:user:profile:"+r.getUserId()).distinct().toList());
        keys.add("ct:meta:tags");
        redis.evict(keys.toArray(new String[0]));
    }
}
