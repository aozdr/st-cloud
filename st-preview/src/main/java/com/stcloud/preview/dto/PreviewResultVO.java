package com.stcloud.preview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "预览结果")
public class PreviewResultVO {

    @Schema(description = "预览类型：image/video/audio/pdf/office/text/unsupported")
    private String type;

    @Schema(description = "预览URL（图片/视频/PDF等）")
    private String url;

    @Schema(description = "文本内容（代码/文本预览时返回）")
    private String content;

    @Schema(description = "文件后缀")
    private String suffix;

    @Schema(description = "状态：ready-就绪 transcoding-转码中")
    private String status;

    @Schema(description = "文件大小(字节)")
    private Long size;

    public static PreviewResultVO of(String type, String url) {
        PreviewResultVO vo = new PreviewResultVO();
        vo.setType(type);
        vo.setUrl(url);
        vo.setStatus("ready");
        return vo;
    }

    public static PreviewResultVO text(String content, String suffix) {
        PreviewResultVO vo = new PreviewResultVO();
        vo.setType("text");
        vo.setContent(content);
        vo.setSuffix(suffix);
        vo.setStatus("ready");
        return vo;
    }

    public static PreviewResultVO transcoding() {
        PreviewResultVO vo = new PreviewResultVO();
        vo.setType("video");
        vo.setStatus("transcoding");
        return vo;
    }

    public static PreviewResultVO unsupported(String suffix) {
        PreviewResultVO vo = new PreviewResultVO();
        vo.setType("unsupported");
        vo.setSuffix(suffix);
        vo.setStatus("ready");
        return vo;
    }
}
