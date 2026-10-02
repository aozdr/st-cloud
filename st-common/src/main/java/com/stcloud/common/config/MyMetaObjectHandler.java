package com.stcloud.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "deleted", Integer.class, 0);

        // 已有租户值或系统实体不需要解析默认租户；真正缺租户的写入仍保留上下文诊断。
        if (metaObject.hasSetter("tenantId") && metaObject.getValue("tenantId") == null) {
            this.strictInsertFill(metaObject, "tenantId", Long.class, TenantContext.getTenantId());
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
