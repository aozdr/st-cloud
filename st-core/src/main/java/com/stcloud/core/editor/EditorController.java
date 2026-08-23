package com.stcloud.core.editor;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.response.Result;
import com.stcloud.core.editor.dto.EditorConfigResponse;
import com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 在线文档编辑端点：
 * <ul>
 *   <li>GET /api/file/{nodeId}/editor/config —— 个人文件编辑配置（登录 + 权限判定）</li>
 *   <li>POST /api/file/{nodeId}/editor/callback —— OnlyOffice 保存回调（仅凭 OnlyOffice JWT 验签，SecurityConfig 放行匿名）</li>
 * </ul>
 */
@Slf4j
@Tag(name = "在线文档编辑", description = "OnlyOffice 编辑器配置与保存回调")
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class EditorController {

    private final EditorPermissionService editorPermissionService;
    private final EditorConfigService editorConfigService;
    private final EditorCallbackService editorCallbackService;
    private final EditorLockService editorLockService;

    @Operation(summary = "获取在线编辑/查看配置（个人文件）")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{nodeId}/editor/config")
    public Result<EditorConfigResponse> editorConfig(@PathVariable Long nodeId,
                                                     @RequestParam(defaultValue = "edit") String mode) {
        EditorPermissionService.EditorAccess access = editorPermissionService.resolvePersonal(nodeId);
        // mode=view：强制只读查看（仅读权限），用于 Office 文件预览；不占编辑位
        boolean canEdit = access.isCanEdit() && !"view".equalsIgnoreCase(mode);
        return Result.success(editorConfigService.generateConfig(nodeId, canEdit, true, true));
    }

    @Operation(summary = "结束编辑会话（前端离开编辑器时主动释放编辑标记，作为 OnlyOffice 回调的兜底）")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{nodeId}/editor/close")
    public Result<Void> editorClose(@PathVariable Long nodeId) {
        UserContext.CurrentUser user = UserContext.getCurrentUser();
        if (user != null && user.getUserId() != null) {
            String editorUserId = String.valueOf(user.getUserId());
            editorLockService.removeEditingUser(nodeId, editorUserId);
            log.info("前端主动结束编辑会话: nodeId={}, editorUser={}", nodeId, editorUserId);
        }
        return Result.success();
    }

    @Operation(summary = "强制重置编辑中状态（清空该文件编辑标记；需 file:reset-editing 权限或 ADMIN）")
    @PreAuthorize("hasAuthority('file:reset-editing') or hasRole('ADMIN')")
    @PostMapping("/{nodeId}/editor/reset")
    public Result<Void> editorReset(@PathVariable Long nodeId) {
        editorLockService.clearEditing(nodeId);
        log.info("强制重置编辑状态: nodeId={}", nodeId);
        return Result.success();
    }

    @Operation(summary = "OnlyOffice 保存/关闭回调")
    @PostMapping("/{nodeId}/editor/callback")
    public ResponseEntity<Map<String, Object>> callback(@PathVariable Long nodeId,
                                                        @RequestBody OnlyOfficeCallbackRequest request) {
        try {
            editorCallbackService.handleCallback(nodeId, request);
            return ResponseEntity.ok(Map.of("error", 0));
        } catch (EditorCallbackRejectedException e) {
            // 验签/归属/大小等校验失败：记录审计并返回对应状态（TC-09 伪造回调被拒）
            log.warn("OnlyOffice 回调被拒绝: nodeId={}, status={}, reason={}",
                    nodeId, e.getHttpStatus(), e.getMessage());
            return ResponseEntity.status(e.getHttpStatus()).body(Map.of("error", 1, "message", e.getMessage()));
        } catch (BusinessException e) {
            // 业务失败（配额/并发冲突等）：返回 500 让 OnlyOffice 按自身策略重试
            log.warn("OnlyOffice 回调业务失败: nodeId={}, code={}, msg={}", nodeId, e.getCode(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", 1, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("OnlyOffice 回调处理异常: nodeId={}", nodeId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", 1));
        }
    }
}
