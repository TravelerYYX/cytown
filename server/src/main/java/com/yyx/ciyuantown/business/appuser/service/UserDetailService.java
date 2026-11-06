package com.yyx.ciyuantown.business.appuser.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.appuser.entity.*;
import com.yyx.ciyuantown.business.appuser.mapper.*;
import com.yyx.ciyuantown.business.appuser.vo.*;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import com.yyx.ciyuantown.business.sys.vo.OfficialAccountDetailVO;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserDetailService {
    private final AppUserMapper users;
    private final AdminUserMapper admins;
    private final AppUserTagMapper userTags;
    private final AppTagMapper tags;
    private final UserStatisticsMapper statistics;
    private final LoginAccountService accounts;
    private static String id(Long id) { return id==null?null:id.toString(); }
    private static String time(LocalDateTime time) { return time==null?null:time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); }
    public List<UserTagVO> tags(Long id) {
        var relations = userTags.selectList(new LambdaQueryWrapper<AppUserTag>().eq(AppUserTag::getUserId,id)
            .orderByAsc(AppUserTag::getSortOrder,AppUserTag::getId));
        var ids = relations.stream().map(AppUserTag::getTagId).filter(Objects::nonNull).toList();
        Map<Long,String> names = ids.isEmpty()?Map.of():tags.selectBatchIds(ids).stream().collect(Collectors.toMap(AppTag::getId,AppTag::getName));
        return relations.stream().map(r -> new UserTagVO(r.getTagId(),r.getTagId()==null?r.getCustomName():names.getOrDefault(r.getTagId(),""),r.getTagId()==null)).toList();
    }
    @Transactional(readOnly=true)
    public AppUserDetailVO app(Long userId) {
        var user = users.selectById(userId);
        if (user==null || user.hasBeenDeleted()) throw new BizException("用户不存在");
        var profile = accounts.info(user,tags(userId));
        return new AppUserDetailVO(id(userId),id(user.getPublicUid()),profile.account(),user.getQqNumber(),user.getEmail(),
            user.getNickname(),user.getAvatar(),profile.signature(),profile.gender(),user.getLevel(),user.getExperience(),profile.cardBackground(),
            profile.tags(),user.getStatus(),profile.hasPassword(),id(user.getOfficialAdminId()),time(user.getCreateTime()),
            time(user.getUpdateTime()),time(user.getLastLoginTime()),statistics.count(userId));
    }
    @Transactional(readOnly=true)
    public OfficialAccountDetailVO official(Long operatorId, Long userId) {
        accounts.requireSuper(operatorId);
        var user = admins.selectById(userId);
        if (user==null || user.hasBeenDeleted() || !Objects.equals(user.getUserType(),1)) throw new BizException("官方账号不存在");
        var creator = user.getCreatedBy()==null?null:admins.selectById(user.getCreatedBy());
        var linked = accounts.linked(userId);
        var community = linked==null?null:app(linked.getId());
        return new OfficialAccountDetailVO(id(userId),user.getUsername(),user.getNickname(),user.getAvatar(),user.getUserType(),
            user.getStatus(),user.getPassword()!=null&&!user.getPassword().isBlank(),id(user.getCreatedBy()),creator==null?null:creator.getNickname(),
            time(user.getCreateTime()),time(user.getUpdateTime()),community,community==null?UserStatisticsVO.empty():community.statistics());
    }
}
