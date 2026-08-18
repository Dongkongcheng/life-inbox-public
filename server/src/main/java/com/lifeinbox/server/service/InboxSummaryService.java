package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiSummaryResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 负责一条 InboxItem 的显式摘要流程；Capture 保存本身不依赖这个 Service。
 */
@Service
public class InboxSummaryService {

    private static final String TYPE_TEXT = "TEXT";
    private static final int MAX_INPUT_CHARS = 20_000;
    private static final int MAX_SUMMARY_CHARS = 2_000;

    private final InboxItemMapper inboxItemMapper;
    private final AiServiceClient aiServiceClient;

    public InboxSummaryService(InboxItemMapper inboxItemMapper, AiServiceClient aiServiceClient) {
        this.inboxItemMapper = inboxItemMapper;
        this.aiServiceClient = aiServiceClient;
    }

    /**
     * 只有 AI 成功返回合法结果后才单列更新 summary，失败时保留全部原始业务数据和旧摘要。
     */
    public InboxItem generateSummary(Long id) {
        InboxItem inboxItem = inboxItemMapper.selectById(id);
        if (inboxItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
        if (!TYPE_TEXT.equals(inboxItem.getType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前只支持为 TEXT 生成摘要");
        }
        if (inboxItem.getContent() == null || inboxItem.getContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TEXT 内容不能为空");
        }
        if (inboxItem.getContent().length() > MAX_INPUT_CHARS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "TEXT 内容不能超过 " + MAX_INPUT_CHARS + " 个字符"
            );
        }

        AiSummaryResponse aiResponse = aiServiceClient.summarize(
                inboxItem.getTitle(),
                inboxItem.getContent()
        );
        String summary = aiResponse.summary() == null ? null : aiResponse.summary().trim();
        if (summary == null || summary.isBlank() || summary.length() > MAX_SUMMARY_CHARS) {
            throw new AiServiceUnavailableException("AI 服务返回了无效摘要");
        }

        // Python 不直接连接 MySQL；Java 验证 AI 输出后才拥有并持久化这份业务结果。
        int updatedRows = inboxItemMapper.updateSummary(id, summary);
        if (updatedRows != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        InboxItem updatedItem = inboxItemMapper.selectById(id);
        if (updatedItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
        return updatedItem;
    }
}
