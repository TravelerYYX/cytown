package com.yyx.ciyuantown.business.post.vo;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data
@EqualsAndHashCode(callSuper=true)
public class PostDetailVO extends PostListVO {
    private String authorBio;
    private String collection;
    private String permission;
    private PollView poll;
    public record PollView(Long id,String question,String endsAt,Integer totalVotes,Long selectedOptionId,java.util.List<PollOptionView> options) {}
    public record PollOptionView(Long id,String text,Integer voteCount) {}
}
