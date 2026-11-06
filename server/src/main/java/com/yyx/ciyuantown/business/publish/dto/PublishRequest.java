package com.yyx.ciyuantown.business.publish.dto;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

public final class PublishRequest {
 private PublishRequest() {}
 public record DraftSave(Long id,Long revision,String mode,String title,String summary,String coverUrl,JsonNode payload) {}
 public record IdParam(Long id) {}
 public record SortParam(List<Long> ids) {}
 public record CustomTopic(String name,String description) {}
 public record Crop(Long timeMs,Double x,Double y,Double width,Double height) {}
 public record Poll(String question,List<String> options,LocalDateTime endsAt) {}
 public record Submit(Long draftId,String mode,Long channelId,String title,String content,JsonNode blocks,
                      List<Long> assetIds,List<Long> topicIds,List<String> customTopics,
                      Boolean allowDownload,Boolean downloadWatermark,Boolean original,Poll poll,Long repostOfPostId) {
  public Submit(Long draftId,String mode,Long channelId,String title,String content,JsonNode blocks,
                List<Long> assetIds,List<Long> topicIds,List<String> customTopics,
                Boolean allowDownload,Boolean downloadWatermark,Boolean original,Poll poll) {
   this(draftId,mode,channelId,title,content,blocks,assetIds,topicIds,customTopics,allowDownload,downloadWatermark,original,poll,null);
  }
 }
 public record Vote(Long optionId) {}
}
