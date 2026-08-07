package com.stcloud.search.init;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.ingest.Processor;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.util.ObjectBuilder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

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
    public static final String ANALYZER_STANDARD = "standard";
    public static final String ANALYZER_NGRAM = "ngram_analyzer";
    public static final String FIELD_ENG_SUFFIX = "eng";
    public static final String FIELD_NGRAM_SUFFIX = "ngram";
    public static final int NGRAM_MIN = 2;
    public static final int NGRAM_MAX = 8;

    private final ElasticsearchClient client;

    @PostConstruct
    public void initialize() {
        try {
            createPipeline();
            boolean created = createIndex();
            if (!created) {
                updateMapping();
            }
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
     * 创建 file_content index：IK 分词器（中文）为主，叠加 standard / ngram 子字段兜底英文子串召回
     * 幂等：已存在则跳过创建（返回 false，由 updateMapping 增量补子字段）
     *
     * @return true 表示新建了索引；false 表示索引已存在
     */
    private boolean createIndex() throws Exception {
        boolean exists = client.indices().exists(e -> e.index(INDEX_NAME)).value();
        if (exists) {
            log.info("ES index '{}' already exists, skip creation", INDEX_NAME);
            return false;
        }
        CreateIndexRequest request = CreateIndexRequest.of(c -> c
                .index(INDEX_NAME)
                .settings(this::analysisSettings)
                .mappings(m -> m
                        .properties(FIELD_FILE_ID, p -> p.keyword(k -> k))
                        .properties(FIELD_FILE_NAME, ikWithSubFields())
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
                                .properties(FIELD_CONTENT, ikWithSubFields())
                        ))
                )
        );
        client.indices().create(request);
        log.info("ES index '{}' created with IK analyzer + ngram sub-fields", INDEX_NAME);
        return true;
    }

    /**
     * 为已存在的索引增量叠加 standard / ngram 子字段（兼容旧索引，无需删数据）
     * 旧索引缺少 ngram_analyzer，putMapping 会失败 -> 先 close/open 注入 analysis 设置，再 putMapping
     */
    private void updateMapping() throws Exception {
        try {
            putSubFieldMapping();
            log.info("ES index '{}' ngram sub-fields already present", INDEX_NAME);
        } catch (Exception e) {
            log.info("Adding ngram analyzer to existing index '{}' (requires close/open): {}", INDEX_NAME, e.getMessage());
            ensureAnalysisSettings();
            putSubFieldMapping();
            log.info("ES index '{}' mapping updated with ngram sub-fields", INDEX_NAME);
        }
    }

    /**
     * 注入 max_ngram_diff 与自定义 ngram_analyzer（analysis 仅能在 closed 索引上更新，故 close -> putSettings -> open）
     */
    private void ensureAnalysisSettings() throws Exception {
        try {
            try {
                client.indices().close(c -> c.index(INDEX_NAME));
            } catch (Exception ce) {
                log.warn("Close index '{}' ignored (may already be closed): {}", INDEX_NAME, ce.getMessage());
            }
            client.indices().putSettings(s -> s.index(INDEX_NAME).settings(this::analysisSettings));
        } finally {
            client.indices().open(o -> o.index(INDEX_NAME));
        }
    }

    /**
     * 给 fileName 与 attachment.content 叠加 eng / ngram 子字段
     */
    private void putSubFieldMapping() throws Exception {
        client.indices().putMapping(m -> m
                .index(INDEX_NAME)
                .properties(FIELD_FILE_NAME, ikWithSubFields())
                .properties(FIELD_ATTACHMENT, p -> p.object(o -> o
                        .properties(FIELD_CONTENT, ikWithSubFields())
                ))
        );
    }

    /**
     * IK 主字段 + standard 子字段（英文整词/标点粘连）+ ngram 子字段（英文词中子串）的多字段属性
     */
    private Function<Property.Builder, ObjectBuilder<Property>> ikWithSubFields() {
        return p -> p.text(t -> t
                .analyzer(ANALYZER_INDEX)
                .searchAnalyzer(ANALYZER_SEARCH)
                .fields(FIELD_ENG_SUFFIX, sp -> sp.text(tt -> tt.analyzer(ANALYZER_STANDARD)))
                .fields(FIELD_NGRAM_SUFFIX, sp -> sp.text(tt -> tt.analyzer(ANALYZER_NGRAM))));
    }

    /**
     * 索引 settings：放开 max_ngram_diff，定义 ngram_analyzer = standard 分词 + lowercase + asciifolding + ngram(2~8)
     */
    private ObjectBuilder<IndexSettings> analysisSettings(IndexSettings.Builder s) {
        return s
                .maxNgramDiff(NGRAM_MAX)
                .analysis(a -> a
                        .analyzer(ANALYZER_NGRAM, an -> an.custom(ca -> ca
                                .tokenizer("standard")
                                .filter(List.of("lowercase", "asciifolding", "ngram_filter"))))
                        .filter("ngram_filter", f -> f.definition(d -> d
                                .ngram(n -> n.minGram(NGRAM_MIN).maxGram(NGRAM_MAX)))));
    }
}