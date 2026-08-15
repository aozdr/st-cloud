package com.stcloud.core.controller;

import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.response.Result;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.NewFileRequest;
import com.stcloud.core.service.NewFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 新建空白文件接口（个人）
 */
@Tag(name = "新建文件", description = "新建空白 txt/docx/xlsx/pptx")
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NewFileController {

    private final NewFileService newFileService;

    @Operation(summary = "新建空白文件（个人目录）")
    @Auditable(action = "CREATE_FILE", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/new")
    public Result<FileNodeVO> createBlankFile(@Valid @RequestBody NewFileRequest request) {
        // 个人新建：parentId 归属校验在服务内完成（owner 校验）
        return Result.success(newFileService.createBlankFile(
                request.getType(), request.getParentId(), null, request.getFileName()));
    }
}
