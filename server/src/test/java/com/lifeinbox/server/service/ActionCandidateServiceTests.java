package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.ActionCandidateResponse;
import com.lifeinbox.server.dto.AiActionCandidateResponse;
import com.lifeinbox.server.dto.AiActionExtractionResponse;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.mapper.ActionCandidateMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionCandidateServiceTests {

    private final InboxItemMapper inboxItemMapper = mock(InboxItemMapper.class);
    private final ActionCandidateMapper actionCandidateMapper = mock(ActionCandidateMapper.class);
    private final InboxSearchableContentService searchableContentService =
            new InboxSearchableContentService(inboxItemMapper);
    private final AiServiceClient aiServiceClient = mock(AiServiceClient.class);
    private final ActionCandidatePersistenceService persistenceService =
            mock(ActionCandidatePersistenceService.class);
    private final ActionCandidateService service = new ActionCandidateService(
            inboxItemMapper,
            actionCandidateMapper,
            searchableContentService,
            aiServiceClient,
            persistenceService
    );

    @Test
    void textTodoIsValidatedAndPersistedAsOneCandidate() {
        when(inboxItemMapper.selectById(1L)).thenReturn(item(1L, "TEXT", "ACTIVE", null,
                "记得整理 Java 面试题。", null));
        when(aiServiceClient.extractActions("记得整理 Java 面试题。"))
                .thenReturn(extraction(true, todo("整理 Java 面试题")));
        List<ActionCandidateResponse> persisted = List.of(response(
                10L,
                ActionCandidateType.TODO,
                "整理 Java 面试题",
                null
        ));
        when(persistenceService.replacePending(anyLong(), anyList())).thenReturn(persisted);

        assertEquals(persisted, service.extract(1L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ValidatedActionCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(persistenceService).replacePending(org.mockito.ArgumentMatchers.eq(1L), candidates.capture());
        assertEquals(1, candidates.getValue().size());
        assertEquals(ActionCandidateType.TODO, candidates.getValue().getFirst().actionType());
        assertEquals("整理 Java 面试题", candidates.getValue().getFirst().title());
    }

    @Test
    void explicitDeadlineAndMultipleActionsKeepAllValidatedFields() {
        when(inboxItemMapper.selectById(2L)).thenReturn(item(
                2L,
                "TEXT",
                "ACTIVE",
                "课程设计",
                "8月25日前提交报告，同时整理参考文献。",
                null
        ));
        when(aiServiceClient.extractActions(anyString())).thenReturn(extraction(
                true,
                new AiActionCandidateResponse(
                        "DEADLINE",
                        "提交课程设计报告",
                        "8月25日前",
                        "2026-08-25",
                        "8月25日前提交报告"
                ),
                todo("整理参考文献")
        ));
        when(persistenceService.replacePending(anyLong(), anyList())).thenReturn(List.of());

        service.extract(2L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ValidatedActionCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(persistenceService).replacePending(org.mockito.ArgumentMatchers.eq(2L), candidates.capture());
        assertEquals(2, candidates.getValue().size());
        ValidatedActionCandidate deadline = candidates.getValue().getFirst();
        assertEquals(ActionCandidateType.DEADLINE, deadline.actionType());
        assertEquals("8月25日前", deadline.deadlineText());
        assertEquals(LocalDate.of(2026, 8, 25), deadline.deadline());
        assertEquals("8月25日前提交报告", deadline.evidence());
    }

    @Test
    void successfulNoActionStillReplacesPendingWithEmptySet() {
        when(inboxItemMapper.selectById(3L)).thenReturn(item(
                3L, "TEXT", "ACTIVE", null, "今天读了一篇文章。", null
        ));
        when(aiServiceClient.extractActions(anyString()))
                .thenReturn(new AiActionExtractionResponse(false, List.of()));
        when(persistenceService.replacePending(3L, List.of())).thenReturn(List.of());

        assertEquals(List.of(), service.extract(3L));
        verify(persistenceService).replacePending(3L, List.of());
    }

    @Test
    void providerFailureNeverStartsCandidateReplacement() {
        when(inboxItemMapper.selectById(4L)).thenReturn(item(
                4L, "TEXT", "ACTIVE", null, "记得复习。", null
        ));
        when(aiServiceClient.extractActions(anyString()))
                .thenThrow(new AiServiceUnavailableException("mock timeout"));

        assertThrows(AiServiceUnavailableException.class, () -> service.extract(4L));
        verify(persistenceService, never()).replacePending(anyLong(), anyList());
    }

    @Test
    void unknownTypeInvalidDateAndInconsistentFlagAreControlledFailures() {
        when(inboxItemMapper.selectById(5L)).thenReturn(item(
                5L, "TEXT", "ACTIVE", null, "正文", null
        ));
        List<AiActionExtractionResponse> invalidResponses = List.of(
                extraction(true, new AiActionCandidateResponse(
                        "MAGIC_ACTION", "非法", null, null, "正文"
                )),
                extraction(true, new AiActionCandidateResponse(
                        "DEADLINE", "非法日期", "2月31日", "2026-02-31", "正文"
                )),
                new AiActionExtractionResponse(false, List.of(todo("整理正文")))
        );

        for (AiActionExtractionResponse response : invalidResponses) {
            when(aiServiceClient.extractActions(anyString())).thenReturn(response);
            assertThrows(AiServiceUnavailableException.class, () -> service.extract(5L));
        }
        verify(persistenceService, never()).replacePending(anyLong(), anyList());
    }

    @Test
    void missingArchivedAndBlankSourcesStopBeforeFastApi() {
        when(inboxItemMapper.selectById(6L)).thenReturn(null);
        when(inboxItemMapper.selectById(7L)).thenReturn(item(
                7L, "TEXT", "ARCHIVED", null, "正文", null
        ));
        when(inboxItemMapper.selectById(8L)).thenReturn(item(
                8L, "FILE", "ACTIVE", "  ", null, "\n\t"
        ));

        ResponseStatusException missing = assertThrows(
                ResponseStatusException.class,
                () -> service.extract(6L)
        );
        ResponseStatusException archived = assertThrows(
                ResponseStatusException.class,
                () -> service.extract(7L)
        );
        ResponseStatusException blank = assertThrows(
                ResponseStatusException.class,
                () -> service.extract(8L)
        );

        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, archived.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatusCode());
        verify(aiServiceClient, never()).extractActions(anyString());
    }

    @Test
    void urlFileAndImageReuseSearchableContent() {
        when(persistenceService.replacePending(anyLong(), anyList())).thenReturn(List.of());
        when(aiServiceClient.extractActions(anyString()))
                .thenReturn(new AiActionExtractionResponse(false, List.of()));
        String[] types = {"URL", "FILE", "IMAGE"};
        for (int index = 0; index < types.length; index++) {
            long id = 20L + index;
            when(inboxItemMapper.selectById(id)).thenReturn(item(
                    id,
                    types[index],
                    "ACTIVE",
                    null,
                    "不得使用的原始字段",
                    "已准备正文-" + types[index]
            ));
            service.extract(id);
        }

        ArgumentCaptor<String> sourceTexts = ArgumentCaptor.forClass(String.class);
        verify(aiServiceClient, times(3)).extractActions(sourceTexts.capture());
        assertEquals(
                List.of("已准备正文-URL", "已准备正文-FILE", "已准备正文-IMAGE"),
                sourceTexts.getAllValues()
        );
    }

    @Test
    void titleProvidesContextAndFinalInputRemainsBounded() {
        String content = "网申截止时间为2026年9月10日。" + "长".repeat(20_000);
        when(inboxItemMapper.selectById(30L)).thenReturn(item(
                30L, "URL", "ACTIVE", "Java 开发实习生", null, content
        ));
        when(aiServiceClient.extractActions(anyString()))
                .thenReturn(new AiActionExtractionResponse(false, List.of()));
        when(persistenceService.replacePending(anyLong(), anyList())).thenReturn(List.of());

        service.extract(30L);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(aiServiceClient).extractActions(text.capture());
        assertTrue(text.getValue().startsWith("标题：\nJava 开发实习生\n\n正文：\n"));
        assertTrue(text.getValue().contains("网申截止时间为2026年9月10日"));
        assertEquals(20_000, text.getValue().length());
    }

    @Test
    void queryRequiresActiveSourceAndReturnsProductDtos() {
        when(inboxItemMapper.selectById(40L)).thenReturn(item(
                40L, "TEXT", "ACTIVE", null, "正文", null
        ));
        com.lifeinbox.server.entity.ActionCandidate entity =
                new com.lifeinbox.server.entity.ActionCandidate();
        entity.setId(41L);
        entity.setInboxItemId(40L);
        entity.setActionType(ActionCandidateType.TODO);
        entity.setTitle("整理正文");
        entity.setEvidence("正文");
        entity.setStatus(ActionCandidateStatus.PENDING);
        when(actionCandidateMapper.selectByInboxItemId(40L)).thenReturn(List.of(entity));

        List<ActionCandidateResponse> results = service.list(40L);

        assertEquals(1, results.size());
        assertEquals(41L, results.getFirst().id());
        assertEquals(ActionCandidateStatus.PENDING, results.getFirst().status());
    }

    private InboxItem item(
            Long id,
            String type,
            String status,
            String title,
            String content,
            String searchableContent
    ) {
        InboxItem item = new InboxItem();
        item.setId(id);
        item.setType(type);
        item.setStatus(status);
        item.setTitle(title);
        item.setContent(content);
        item.setSearchableContent(searchableContent);
        return item;
    }

    private AiActionExtractionResponse extraction(
            boolean hasAction,
            AiActionCandidateResponse... actions
    ) {
        return new AiActionExtractionResponse(hasAction, List.of(actions));
    }

    private AiActionCandidateResponse todo(String title) {
        return new AiActionCandidateResponse("TODO", title, null, null, title);
    }

    private ActionCandidateResponse response(
            Long id,
            ActionCandidateType type,
            String title,
            LocalDate deadline
    ) {
        return new ActionCandidateResponse(
                id,
                1L,
                type,
                title,
                null,
                deadline,
                title,
                ActionCandidateStatus.PENDING,
                null,
                null
        );
    }
}
