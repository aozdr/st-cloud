package com.stcloud.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.stcloud.common.context.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

@Configuration
public class MyBatisPlusConfig {

    /**
     * 忽略租户隔离的表（系统级表不需要租户隔离）
     */
    private static final Set<String> IGNORE_TABLES = new HashSet<>();

    static {
        IGNORE_TABLES.add("sys_tenant");
        IGNORE_TABLES.add("sys_permission");
        IGNORE_TABLES.add("sys_jwt_secret");
        IGNORE_TABLES.add("event_log");
        IGNORE_TABLES.add("sys_config");
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 租户隔离拦截器
        TenantLineInnerInterceptor tenantInterceptor = new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(TenantContext.getTenantId());
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 私有云模式跳过租户隔离
                if (TenantContext.isPrivateMode()) {
                    return true;
                }
                return IGNORE_TABLES.contains(tableName);
            }
        });
        interceptor.addInnerInterceptor(tenantInterceptor);

        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        // 乐观锁插件
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        return interceptor;
    }
}
