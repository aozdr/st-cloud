package com.stcloud.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.Data;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Elasticsearch 客户端配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "stcloud.elasticsearch")
public class ElasticsearchConfig {

    private String uris = "http://127.0.0.1:9200";
    private int connectTimeout = 5000;
    private int socketTimeout = 30000;

    @Bean
    public RestClient restClient() {
        URI uri = URI.create(uris);
        HttpHost host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
        return RestClient.builder(host)
                .setRequestConfigCallback(builder -> builder
                        .setConnectTimeout(connectTimeout)
                        .setSocketTimeout(socketTimeout))
                .build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
