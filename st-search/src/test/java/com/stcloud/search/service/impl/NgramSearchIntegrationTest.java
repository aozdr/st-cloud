package com.stcloud.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.util.ObjectBuilder;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端集成测试：连真实 ES（127.0.0.1:9200）验证 IK + ngram 子字段方案的真实召回。
 * 复刻 SearchIndexInitializer 的索引 mapping 与 SearchServiceImpl 的 bool 查询。
 * <p>
 * 前置：ES 8.x + IK 插件 + ingest-attachment 插件已运行。
 */
@DisplayName("ES 搜索分词集成测试（真实 ES）")
class NgramSearchIntegrationTest {

    private static final String TEMP_INDEX = "test_ngram_integration";
    private static final String PIPELINE = "file-content-pipeline";
    private static final String FIELD_FILE_NAME = "fileName";
    private static final String FIELD_ATTACHMENT = "attachment";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_OWNER_ID = "ownerId";
    private static final String CONTENT_FIELD = FIELD_ATTACHMENT + "." + FIELD_CONTENT;
    private static final String ANALYZER_INDEX = "ik_max_word";
    private static final String ANALYZER_SEARCH = "ik_smart";
    private static final String ANALYZER_STANDARD = "standard";
    private static final String ANALYZER_NGRAM = "ngram_analyzer";
    private static final String ENG = "eng";
    private static final String NGRAM = "ngram";

    private static ElasticsearchClient client;

    @BeforeAll
    static void setup() throws Exception {
        RestClient restClient = RestClient.builder(new HttpHost("127.0.0.1", 9200, "http")).build();
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));

        try {
            client.indices().delete(d -> d.index(TEMP_INDEX));
        } catch (Exception ignored) {
        }

        CreateIndexRequest request = CreateIndexRequest.of(c -> c
                .index(TEMP_INDEX)
                .settings(NgramSearchIntegrationTest::analysisSettings)
                .mappings(m -> m
                        .properties(FIELD_FILE_NAME, ikWithSubFields())
                        .properties(FIELD_OWNER_ID, p -> p.long_(l -> l))
                        .properties(FIELD_ATTACHMENT, p -> p.object(o -> o
                                .properties(FIELD_CONTENT, ikWithSubFields())
                        ))
                )
        );
        client.indices().create(request);

        String text = "SpringBoot microservices architecture design. "
                + "The microservices pattern enables scalable deployment. "
                + "第三季度微服务架构报告，营收增长。";
        String base64 = java.util.Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));

        client.index(i -> i
                .index(TEMP_INDEX)
                .pipeline(PIPELINE)
                .document(Map.of(
                        FIELD_FILE_NAME, "SpringBoot microservices guide.pdf",
                        FIELD_OWNER_ID, 1L,
                        "suffix", "pdf",
                        "data", base64
                ))
        );
        client.indices().refresh(r -> r.index(TEMP_INDEX));
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (client != null) {
            try {
                client.indices().delete(d -> d.index(TEMP_INDEX));
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("英文词中子串 'services' 能命中 microservices")
    void testEnglishSubstring_services() throws Exception {
        assertTrue(search("services") >= 1, "services 应命中 microservices");
    }

    @Test
    @DisplayName("英文词中子串 'arch' 能命中 architecture")
    void testEnglishSubstring_arch() throws Exception {
        assertTrue(search("arch") >= 1, "arch 应命中 architecture");
    }

    @Test
    @DisplayName("驼峰拆词 'boot' 能命中 SpringBoot")
    void testEnglishSubstring_boot() throws Exception {
        assertTrue(search("boot") >= 1, "boot 应命中 SpringBoot");
    }

    @Test
    @DisplayName("中文 '报告' 走 IK 主字段命中")
    void testChinese_baogao() throws Exception {
        assertTrue(search("报告") >= 1, "报告 应由 IK 命中");
    }

    @Test
    @DisplayName("中文 '微服务' 走 IK 主字段命中")
    void testChinese_weifuwu() throws Exception {
        assertTrue(search("微服务") >= 1, "微服务 应由 IK 命中");
    }

    /**
     * 复刻 SearchServiceImpl 的 bool 查询：9 个 should + minimumShouldMatch=1 + ownerId 过滤
     */
    private long search(String keyword) throws Exception {
        String escapedKw = keyword.toLowerCase().replaceAll("[*?\\\\]", "\\\\$0");
        SearchResponse<Map> response = client.search(s -> s
                .index(TEMP_INDEX)
                .size(20)
                .query(q -> q.bool(b -> {
                    b.should(m -> m.match(mm -> mm.field(CONTENT_FIELD).query(keyword)));
                    b.should(m -> m.matchPhrasePrefix(mpp -> mpp.field(CONTENT_FIELD).query(keyword)));
                    b.should(m -> m.match(mm -> mm.field(FIELD_FILE_NAME).query(keyword)));
                    b.should(m -> m.matchPhrasePrefix(mpp -> mpp.field(FIELD_FILE_NAME).query(keyword)));
                    b.should(m -> m.wildcard(w -> w.field(FIELD_FILE_NAME).wildcard("*" + escapedKw + "*")));
                    b.should(m -> m.match(mm -> mm.field(CONTENT_FIELD + "." + ENG).query(keyword)));
                    b.should(m -> m.match(mm -> mm.field(CONTENT_FIELD + "." + NGRAM).query(keyword)));
                    b.should(m -> m.match(mm -> mm.field(FIELD_FILE_NAME + "." + ENG).query(keyword)));
                    b.should(m -> m.match(mm -> mm.field(FIELD_FILE_NAME + "." + NGRAM).query(keyword)));
                    b.minimumShouldMatch("1");
                    b.filter(f -> f.term(t -> t.field(FIELD_OWNER_ID).value(1L)));
                    return b;
                })), Map.class);
        return response.hits().total() != null ? response.hits().total().value() : 0;
    }

    private static Function<Property.Builder, ObjectBuilder<Property>> ikWithSubFields() {
        return p -> p.text(t -> t
                .analyzer(ANALYZER_INDEX)
                .searchAnalyzer(ANALYZER_SEARCH)
                .fields(ENG, sp -> sp.text(tt -> tt.analyzer(ANALYZER_STANDARD)))
                .fields(NGRAM, sp -> sp.text(tt -> tt.analyzer(ANALYZER_NGRAM))));
    }

    private static ObjectBuilder<IndexSettings> analysisSettings(IndexSettings.Builder s) {
        return s
                .maxNgramDiff(8)
                .analysis(a -> a
                        .analyzer(ANALYZER_NGRAM, an -> an.custom(ca -> ca
                                .tokenizer("standard")
                                .filter(List.of("lowercase", "asciifolding", "ngram_filter"))))
                        .filter("ngram_filter", f -> f.definition(d -> d
                                .ngram(n -> n.minGram(2).maxGram(8)))));
    }
}