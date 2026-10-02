package com.stcloud.common.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.stcloud.common.context.TenantContext;
import lombok.Data;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class MyMetaObjectHandlerTenantTest {
    @Data @TableName("tenant_fill_fixture")
    static class Scoped {
        @TableId Long id;
        @TableField(fill=FieldFill.INSERT) Long tenantId;
    }
    @Data @TableName("global_fill_fixture")
    static class Global { @TableId Long id; }
    private final MyMetaObjectHandler handler=new MyMetaObjectHandler();
    private final Logger logger=(Logger) LoggerFactory.getLogger(TenantContext.class);
    private final ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs=new ListAppender<>();
    @BeforeEach void setup() {
        TenantContext.clear(); logs.start(); logger.addAppender(logs);
        for(Class<?> type:new Class<?>[]{Scoped.class,Global.class}) {
            var assistant=new MapperBuilderAssistant(new MybatisConfiguration(),type.getName());
            assistant.setCurrentNamespace(type.getName()); TableInfoHelper.initTableInfo(assistant,type);
        }
    }
    @AfterEach void cleanup(){logger.detachAppender(logs);logs.stop();TenantContext.clear();}
    @Test void suppliedTenantDoesNotResolveFallback(){
        Scoped entity=new Scoped();entity.setTenantId(71L);
        handler.insertFill(SystemMetaObject.forObject(entity));
        assertEquals(71L,entity.getTenantId());assertTrue(logs.list.isEmpty());
    }
    @Test void systemEntityDoesNotResolveTenant(){
        handler.insertFill(SystemMetaObject.forObject(new Global()));assertTrue(logs.list.isEmpty());
    }
    @Test void actualMissingContextRetainsDiagnostic(){
        Scoped entity=new Scoped();handler.insertFill(SystemMetaObject.forObject(entity));
        assertEquals(1L,entity.getTenantId());
        assertTrue(logs.list.stream().anyMatch(event->event.getFormattedMessage().contains("租户上下文未设置")));
    }
    @Test void explicitContextFillsCorrectTenant(){
        TenantContext.setTenantId(72L);Scoped entity=new Scoped();handler.insertFill(SystemMetaObject.forObject(entity));
        assertEquals(72L,entity.getTenantId());assertTrue(logs.list.isEmpty());
    }
}
