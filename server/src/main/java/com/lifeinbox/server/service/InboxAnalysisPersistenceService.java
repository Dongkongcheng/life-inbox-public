package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import com.lifeinbox.server.mapper.InboxTagMapper;
import com.lifeinbox.server.mapper.TagMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 只负责把一次完整 AnalyzeResult 原子替换进 MySQL。 */
@Service
public class InboxAnalysisPersistenceService {

    private final InboxItemMapper inboxItemMapper;
    private final TagMapper tagMapper;
    private final InboxTagMapper inboxTagMapper;

    public InboxAnalysisPersistenceService(
            InboxItemMapper inboxItemMapper,
            TagMapper tagMapper,
            InboxTagMapper inboxTagMapper
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.tagMapper = tagMapper;
        this.inboxTagMapper = inboxTagMapper;
    }

    /**
     * Summary、Category 和 Tags 属于同一次分析；任一步失败都回滚，保留旧结果。
     * 该方法由另一个 Spring Bean 调用，确保 @Transactional 代理真正生效。
     */
    @Transactional
    public InboxItem replaceAnalysis(
            Long inboxItemId,
            String summary,
            String category,
            List<NormalizedTag> tags
    ) {
        // 先更新主表取得行锁，使同一条记录的并发重分析按顺序完整替换，而不是混合 Tags。
        int updatedRows = inboxItemMapper.updateAnalysis(inboxItemId, summary, category);
        if (updatedRows != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        inboxTagMapper.deleteByInboxItemId(inboxItemId);
        for (NormalizedTag tag : tags) {
            tagMapper.upsertTag(tag.name(), tag.normalizedName());
            Long tagId = tagMapper.selectIdByNormalizedName(tag.normalizedName());
            if (tagId == null || inboxTagMapper.insertRelation(inboxItemId, tagId) != 1) {
                throw new IllegalStateException("AI 标签保存失败");
            }
        }

        InboxItem updatedItem = inboxItemMapper.selectById(inboxItemId);
        if (updatedItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
        updatedItem.setTags(inboxTagMapper.selectTagNamesByInboxItemId(inboxItemId));
        return updatedItem;
    }
}
