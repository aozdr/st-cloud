package com.stcloud.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "更新冲突策略")
public class UpdateConflictStrategyRequest {

    @Schema(description = "冲突策略：keep_both / latest_wins / server_wins / local_wins")
    @NotBlank(message = "冲突策略不能为空")
    @Pattern(regexp = "keep_both|latest_wins|server_wins|local_wins",
             message = "冲突策略必须是 keep_both / latest_wins / server_wins / local_wins")
    private String conflictStrategy;
}