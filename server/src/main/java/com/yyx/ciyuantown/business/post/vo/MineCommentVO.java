package com.yyx.ciyuantown.business.post.vo;

import lombok.Getter;
import lombok.Setter;

/** Authenticated author's history, including comments on unavailable posts. */
@Getter @Setter
public class MineCommentVO extends CommentVO {
    private String postTitle;
    private String postMode;
    private Boolean postAvailable;
}
