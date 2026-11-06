package com.yyx.ciyuantown.business.report;

import com.yyx.ciyuantown.business.chat.ChatService;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class UserBlockService {
    private final JdbcTemplate db;
    private final ChatService chat;
    public boolean blocked(Long owner,long target) {
        return owner!=null && db.queryForObject("SELECT COUNT(*) FROM user_block WHERE owner_id=? AND target_id=?",Integer.class,owner,target)>0;
    }
    @Transactional
    public boolean set(long owner,long target,boolean blocked) {
        if(owner==target)throw new BizException("不能拉黑自己");
        // Share the send lock, so block and delivery have a single ordering.
        chat.lockStreams(owner,target);
        if(blocked)db.update("INSERT IGNORE INTO user_block(owner_id,target_id) VALUES(?,?)",owner,target);
        else db.update("DELETE FROM user_block WHERE owner_id=? AND target_id=?",owner,target);
        return blocked;
    }
}
