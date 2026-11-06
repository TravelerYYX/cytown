package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("upload_asset")
public class UploadAsset {
 @TableId(type=IdType.AUTO) private Long id; private Long ownerId; private String mediaType; private String url;
 private String originalName; private String contentType; private Long sizeBytes; private Integer width; private Integer height;
 private Long durationMs; private String coverUrl; private Long coverTimeMs; private String cropJson; private String status;
 private String errorMessage; private Boolean attached; private LocalDateTime createTime; private LocalDateTime updateTime;
 private String previewUrl; private String originalUrl; private String hlsUrl; private String qualitiesJson; private String resourceVersion;
}
