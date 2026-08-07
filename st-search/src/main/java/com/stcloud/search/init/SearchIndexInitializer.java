package com.stcloud.search.init;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.ingest.Processor;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用启动时创建 ES ingest pipeline 和 index（幂等）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexInitializer {

    public static final String INDEX_NAME = "file_content";
    public static final String PIPELINE_NAME = "file-content-pipeline";
    public static final String FIELD_DATA = "data";
    public static final String FIELD_ATTACHMENT = "attachment";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_FILE_ID = "fileId";
    public static final String FIELD_FILE_NAME = "fileName";
    public static final String FIELD_OWNER_ID = "ownerId";
    public static final String FIELD_STORAGE_PATH = "storagePath";
    public static final String FIELD_CONTENT_TYPE = "contentType";
    public static final String FIELD_SUFFIX = "suffix";
    public static final String FIELD_FILE_SIZE = "fileSize";
    public static final String FIELD_PATH = "path";
    public static final String FIELD_NODE_TYPE = "nodeType";
    public static final String FIELD_CREATED_AT = "createdAt";
    public static final String FIELD_UPDATED_AT = "updatedAt";
    public static final String ANALYZER_INDEX = "ik_max_word";
    public static final String ANALYZER_SEARCH = "ik_smart";

    private final ElasticsearchClient client;

    @PostConstruct
    public void initialize() {
        try {
            createPipeline();
            createIndex();
            log.info("ES search index and pipeline initialized successfully");
        } catch (Exception e) {
            log.warn("ES search index initialization failed, search may not work: {}", e.getMessage());
        }
    }

    /**
     * 创建 ingest pipeline（attachment processor）
     * putPipeline 是幂等操作，已存在则覆盖
     */
    private void createPipeline() throws Exception {
        client.ingest().putPipeline(p -> p
                .id(PIPELINE_NAME)
                .description("Extract file content via Tika attachment plugin")
                .processors(List.of(
                        Processor.of(proc -> proc.attachment(att -> att.field(FIELD_DATA)))
                ))
        );
        log.info("ES pipeline '{}' ensured", PIPELINE_NAME);
    }

    /**
     * 创建 file_content index，mapping 使用 IK 分词器
     * 幂等：已存在则跳过
     */
    private void createIndex() throws Exception {
        boolean exists = client.indices().exists(e -> e.index(INDEX_NAME)).value();
        if (exists) {
            log.info("ES index '{}' already exists, skip creation", INDEX_NAME);
            return;
        }
        CreateIndexRequest request = CreateIndexRequest.of(c -> c
                .index(INDEX_NAME)
                .mappings(m -> m
                        .properties(FIELD_FILE_ID, p -> p.keyword(k -> k))
                        .properties(FIELD_FILE_NAME, p -> p.text(t -> t.analyzer(ANALYZER_INDEX).searchAnalyzer(ANALYZER_SEARCH)))
                        .properties(FIELD_OWNER_ID, p -> p.long_(l -> l))
                        .properties(FIELD_STORAGE_PATH, p -> p.keyword(k -> k))
                        .properties(FIELD_CONTENT_TYPE, p -> p.keyword(k -> k))
                        .properties(FIELD_SUFFIX, p -> p.keyword(k -> k))
                        .properties(FIELD_FILE_SIZE, p -> p.long_(l -> l))
                        .properties(FIELD_PATH, p -> p.keyword(k -> k))
                        .properties(FIELD_NODE_TYPE, p -> p.integer(i -> i))
                        .properties(FIELD_CREATED_AT, p -> p.date(d -> d))
                        .properties(FIELD_UPDATED_AT, p -> p.date(d -> d))
                        .properties(FIELD_ATTACHMENT, p -> p.object(o -> o
                                .properties(FIELD_CONTENT, pp -> pp.text(t -> t.analyzer(ANALYZER_INDEX).searchAnalyzer(ANALYZER_SEARCH)))
                        ))
                )
        );
        client.indices().create(request);
        log.info("ES index '{}' created with IK analyzer mapping", INDEX_NAME);
    }
}
