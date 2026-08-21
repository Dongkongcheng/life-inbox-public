package com.lifeinbox.server.controller;

import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.service.InboxService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchControllerTests {

    @Test
    void searchReturnsTheExistingInboxItemResponseShape() throws Exception {
        InboxService inboxService = mock(InboxService.class);
        InboxItem item = new InboxItem();
        item.setId(1L);
        item.setTitle("Redis 分布式锁");
        item.setSummary("介绍 Redis 在高并发环境下的使用方式。");
        when(inboxService.search(" Redis ")).thenReturn(List.of(item));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new SearchController(inboxService))
                .build();

        mockMvc.perform(get("/api/search").param("q", " Redis "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("Redis 分布式锁"))
                .andExpect(jsonPath("$[0].summary").value("介绍 Redis 在高并发环境下的使用方式。"));
        verify(inboxService).search(" Redis ");
    }

    @Test
    void blankQueryReturnsControlledBadRequest() throws Exception {
        InboxService inboxService = mock(InboxService.class);
        when(inboxService.search(" ")).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索关键词不能为空")
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new SearchController(inboxService))
                .build();

        mockMvc.perform(get("/api/search").param("q", " "))
                .andExpect(status().isBadRequest());
    }
}
