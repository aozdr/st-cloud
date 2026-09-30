package com.stcloud.team.controller;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.MoveRequest;
import com.stcloud.core.editor.EditorConfigService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.NewFileService;
import com.stcloud.core.text.TextFileService;
import com.stcloud.team.service.TeamService;
import com.stcloud.team.util.ActiveTracker;
import com.stcloud.team.util.TeamActivityHelper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 复制入口先完成全部源 view 与目标 upload 校验，之后才调用写服务。 */
class TeamControllerCopyPermissionTest {
    private final TeamService team = mock(TeamService.class);
    private final FileService files = mock(FileService.class);
    private final TeamActivityHelper activities = mock(TeamActivityHelper.class);
    private final TeamController controller = new TeamController(team, files,
            mock(NewFileService.class), activities, mock(ActiveTracker.class),
            mock(EditorConfigService.class), mock(TextFileService.class));

    private MoveRequest request(Long target, Long... sources) {
        MoveRequest request = new MoveRequest();
        request.setNodeIds(List.of(sources));
        request.setTargetParentId(target);
        return request;
    }

    private BusinessException denied() {
        return new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "isolated ACL denial");
    }

    @Test
    void targetWithoutUploadRejectsBeforeAnyCopySideEffect() {
        doThrow(denied()).when(team).requirePermissions(10L, 20L, "upload");
        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.copyFiles(10L, request(20L, 11L)));
        assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), error.getCode());
        verify(team).requirePermissions(10L, 11L, "view");
        verifyNoInteractions(files, activities);
    }

    @Test
    void sourceViewAloneIsEnoughWhenTargetAllowsUpload() {
        controller.copyFiles(10L, request(20L, 11L));
        var order = inOrder(team, files);
        order.verify(team).requirePermissions(10L, 11L, "view");
        order.verify(team).requirePermissions(10L, 20L, "upload");
        order.verify(files).copyTeamFiles(10L, List.of(11L), 20L);
        verify(team, never()).requirePermissions(10L, 11L, "download");
    }

    @Test
    void everySourceIsPrecheckedBeforeBatchWriteInBothOrders() {
        for (List<Long> order : List.of(List.of(11L, 12L), List.of(12L, 11L))) {
            TeamService localTeam = mock(TeamService.class);
            FileService localFiles = mock(FileService.class);
            TeamActivityHelper localActivities = mock(TeamActivityHelper.class);
            TeamController local = new TeamController(localTeam, localFiles,
                    mock(NewFileService.class), localActivities, mock(ActiveTracker.class),
                    mock(EditorConfigService.class), mock(TextFileService.class));
            doThrow(denied()).when(localTeam).requirePermissions(10L, 12L, "view");
            MoveRequest request = new MoveRequest();
            request.setNodeIds(order);
            request.setTargetParentId(20L);
            assertThrows(BusinessException.class, () -> local.copyFiles(10L, request));
            verifyNoInteractions(localFiles, localActivities);
        }
    }

    @Test
    void rootTargetStillRequiresUploadAtSubmissionTime() {
        doThrow(denied()).when(team).requirePermissions(10L, 0L, "upload");
        assertThrows(BusinessException.class, () -> controller.copyFiles(10L, request(0L, 11L)));
        verifyNoInteractions(files, activities);
    }
}
