package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.entity.InboxEntity;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxEntityMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import com.lifeinbox.server.mapper.InboxKeywordMapper;
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
    private final InboxKeywordMapper inboxKeywordMapper;
    private final InboxEntityMapper inboxEntityMapper;

    public InboxAnalysisPersistenceService(
            InboxItemMapper inboxItemMapper,
            TagMapper tagMapper,
            InboxTagMapper inboxTagMapper,
            InboxKeywordMapper inboxKeywordMapper,
            InboxEntityMapper inboxEntityMapper
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.tagMapper = tagMapper;
        this.inboxTagMapper = inboxTagMapper;
        this.inboxKeywordMapper = inboxKeywordMapper;
        this.inboxEntityMapper = inboxEntityMapper;
    }

    /**
     * 五类结果属于同一次分析；任一步失败都回滚，保留之前完整的成功结果。
     * 该方法由另一个 Spring Bean 调用，确保 @Transactional 代理真正生效。
     */
    @Transactional
    public InboxItem replaceAnalysis(
            Long inboxItemId,
            String attemptId,
            String summary,
            String category,
            List<NormalizedTag> tags,
            List<String> keywords,
            List<NormalizedEntity> entities
    ) {
        // 第一条写入同时取得行锁并校验 Attempt；旧请求不能碰主表或任何分析子表。
        int updatedRows = inboxItemMapper.updateAnalysis(
                inboxItemId,
                attemptId,
                AiProcessingStatus.PROCESSING,
                summary,
                category
        );
        if (updatedRows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI 分析尝试已失效");
        }

        inboxTagMapper.deleteByInboxItemId(inboxItemId);
        for (NormalizedTag tag : tags) {
            tagMapper.upsertTag(tag.name(), tag.normalizedName());
            Long tagId = tagMapper.selectIdByNormalizedName(tag.normalizedName());
            if (tagId == null || inboxTagMapper.insertRelation(inboxItemId, tagId) != 1) {
                throw new IllegalStateException("AI 标签保存失败");
            }
        }

        inboxKeywordMapper.deleteByInboxItemId(inboxItemId);
        for (String keyword : keywords) {
            if (inboxKeywordMapper.insertKeyword(inboxItemId, keyword) != 1) {
                throw new IllegalStateException("AI 关键词保存失败");
            }
        }

        inboxEntityMapper.deleteByInboxItemId(inboxItemId);
        for (NormalizedEntity entity : entities) {
            if (inboxEntityMapper.insertEntity(inboxItemId, entity.name(), entity.type()) != 1) {
                throw new IllegalStateException("AI 实体保存失败");
            }
        }

        // SUCCESS 必须最后写入并与五类结果一起提交；此前任一步失败都会整体回滚。
        int statusRows = inboxItemMapper.markAnalysisSuccess(
                inboxItemId,
                attemptId,
                AiProcessingStatus.PROCESSING,
                AiProcessingStatus.SUCCESS
        );
        if (statusRows != 1) {
            throw new IllegalStateException("AI 成功状态保存失败");
        }

        InboxItem updatedItem = inboxItemMapper.selectById(inboxItemId);
        if (updatedItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
        updatedItem.setTags(inboxTagMapper.selectTagNamesByInboxItemId(inboxItemId));
        updatedItem.setKeywords(inboxKeywordMapper.selectKeywordsByInboxItemId(inboxItemId));
        updatedItem.setEntities(
                inboxEntityMapper.selectEntitiesByInboxItemId(inboxItemId).stream()
                        .map(this::toResponse)
                        .toList()
        );
        return updatedItem;
    }

    private AiEntityResponse toResponse(InboxEntity entity) {
        return new AiEntityResponse(entity.getName(), entity.getType());
    }
}
