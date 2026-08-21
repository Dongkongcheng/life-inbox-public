package com.lifeinbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.dto.CreateInboxItemRequest;
import com.lifeinbox.server.entity.InboxEntity;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxEntityMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import com.lifeinbox.server.mapper.InboxKeywordMapper;
import com.lifeinbox.server.mapper.InboxTagMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Inbox 核心业务入口：统一处理不同 Capture 类型，并保持 Controller 只负责 HTTP 协议转换。
 */
@Service
public class InboxService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final String TYPE_TEXT = "TEXT";
    private static final String TYPE_URL = "URL";
    private static final String TYPE_FILE = "FILE";
    private static final String TYPE_IMAGE = "IMAGE";
    private static final String FILE_URL_PREFIX = "/api/files/";
    private static final int MAX_TITLE_LENGTH = 255;

    private final InboxItemMapper inboxItemMapper;
    private final InboxTagMapper inboxTagMapper;
    private final InboxKeywordMapper inboxKeywordMapper;
    private final InboxEntityMapper inboxEntityMapper;
    private final UrlMetadataService urlMetadataService;
    private final FileStorageService fileStorageService;
    private final InboxAnalysisStatusService analysisStatusService;

    public InboxService(
            InboxItemMapper inboxItemMapper,
            InboxTagMapper inboxTagMapper,
            InboxKeywordMapper inboxKeywordMapper,
            InboxEntityMapper inboxEntityMapper,
            UrlMetadataService urlMetadataService,
            FileStorageService fileStorageService,
            InboxAnalysisStatusService analysisStatusService
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.inboxTagMapper = inboxTagMapper;
        this.inboxKeywordMapper = inboxKeywordMapper;
        this.inboxEntityMapper = inboxEntityMapper;
        this.urlMetadataService = urlMetadataService;
        this.fileStorageService = fileStorageService;
        this.analysisStatusService = analysisStatusService;
    }

    public List<InboxItem> list() {
        // 归档只是修改状态而不是删除；主 Inbox 因此只查询 ACTIVE 数据。
        LambdaQueryWrapper<InboxItem> query = new LambdaQueryWrapper<>();
        query.eq(InboxItem::getStatus, STATUS_ACTIVE);
        List<InboxItem> items = inboxItemMapper.selectList(query);
        // 当前数据量很小，逐条聚合三类分析子表；API 始终返回数组而不是数据库关系实体。
        for (InboxItem item : items) {
            item.setTags(inboxTagMapper.selectTagNamesByInboxItemId(item.getId()));
            item.setKeywords(inboxKeywordMapper.selectKeywordsByInboxItemId(item.getId()));
            item.setEntities(
                    inboxEntityMapper.selectEntitiesByInboxItemId(item.getId()).stream()
                            .map(this::toEntityResponse)
                            .toList()
            );
            item.setAiProcessingStale(analysisStatusService.isProcessingStale(item));
        }
        return items;
    }

    private AiEntityResponse toEntityResponse(InboxEntity entity) {
        return new AiEntityResponse(entity.getName(), entity.getType());
    }

    public InboxItem create(CreateInboxItemRequest request) {
        InboxItem inboxItem = new InboxItem();
        inboxItem.setType(request.getType());
        inboxItem.setTitle(request.getTitle());

        if (TYPE_TEXT.equals(request.getType())) {
            if (isBlank(request.getContent())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TEXT 类型的 content 不能为空");
            }
            inboxItem.setContent(request.getContent());
        } else if (TYPE_URL.equals(request.getType())) {
            String sourceUrl = validateAndNormalizeUrl(request.getSourceUrl());
            inboxItem.setSourceUrl(sourceUrl);
            if (isBlank(request.getTitle())) {
                // 只有用户未填写标题时才抓取网页，避免覆盖用户主动输入的标题。
                inboxItem.setTitle(urlMetadataService.resolveTitle(sourceUrl));
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂不支持该 InboxItem 类型");
        }

        inboxItemMapper.insert(inboxItem);
        return inboxItemMapper.selectById(inboxItem.getId());
    }

    @Transactional
    public InboxItem createFile(MultipartFile file, String title) {
        FileStorageService.StoredFile storedFile = fileStorageService.store(file);
        return createStoredItem(storedFile, title, TYPE_FILE);
    }

    @Transactional
    public InboxItem createImage(MultipartFile file, String title) {
        FileStorageService.StoredFile storedFile = fileStorageService.storeImage(file);
        return createStoredItem(storedFile, title, TYPE_IMAGE);
    }

    private InboxItem createStoredItem(
            FileStorageService.StoredFile storedFile,
            String title,
            String type
    ) {
        try {
            String normalizedTitle = title == null ? null : title.trim();
            if (normalizedTitle != null && normalizedTitle.length() > MAX_TITLE_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title 长度不能超过 255");
            }

            InboxItem inboxItem = new InboxItem();
            inboxItem.setType(type);
            inboxItem.setTitle(
                    isBlank(normalizedTitle)
                            ? defaultFileTitle(storedFile.originalFilename())
                            : normalizedTitle
            );
            inboxItem.setFileUrl(FILE_URL_PREFIX + storedFile.storedName());
            inboxItem.setStatus(STATUS_ACTIVE);
            inboxItem.setFavorite(0);

            int insertedRows = inboxItemMapper.insert(inboxItem);
            if (insertedRows != 1) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "上传记录保存失败");
            }

            InboxItem savedItem = inboxItemMapper.selectById(inboxItem.getId());
            if (savedItem == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "上传记录保存失败");
            }
            return savedItem;
        } catch (RuntimeException | Error exception) {
            // 磁盘写入先于数据库 INSERT；后续任一步失败时删除文件，避免留下孤儿文件。
            fileStorageService.delete(storedFile.storedName());
            throw exception;
        }
    }

    private String defaultFileTitle(String originalFilename) {
        return originalFilename.length() <= MAX_TITLE_LENGTH
                ? originalFilename
                : originalFilename.substring(0, MAX_TITLE_LENGTH);
    }

    private String validateAndNormalizeUrl(String sourceUrl) {
        if (isBlank(sourceUrl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL 类型的 sourceUrl 不能为空");
        }

        String normalizedUrl = sourceUrl.trim();
        try {
            URI uri = new URI(normalizedUrl);
            String scheme = uri.getScheme();
            // 当前只允许可由 Metadata 服务安全处理的 HTTP(S) 地址。
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceUrl 必须是合法的 HTTP 或 HTTPS URL");
            }
            return normalizedUrl;
        } catch (URISyntaxException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "sourceUrl 必须是合法的 HTTP 或 HTTPS URL"
            );
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public void delete(Long id) {
        int deletedRows = inboxItemMapper.deleteById(id);
        if (deletedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
    }

    public void archive(Long id) {
        InboxItem inboxItem = inboxItemMapper.selectById(id);
        if (inboxItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        // 只更新归档列，避免并发 Analyze 时用查询到的旧实体覆盖 AI 状态或结果。
        int updatedRows = inboxItemMapper.updateInboxStatus(id, STATUS_ARCHIVED);
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
    }

    public void favorite(Long id) {
        updateFavorite(id, 1);
    }

    public void unfavorite(Long id) {
        updateFavorite(id, 0);
    }

    private void updateFavorite(Long id, int favorite) {
        InboxItem inboxItem = inboxItemMapper.selectById(id);
        if (inboxItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        int updatedRows = inboxItemMapper.updateFavorite(id, favorite);
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
    }
}
