package com.stcloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实响应DTO和生产Jackson配置验证跨模块ID契约；不是业务HTTP集成。 */
class LargeIdContractTest {
    @ParameterizedTest
    @ValueSource(strings={"9007199254740993","9007199254740995","9223372036854775806"})
    void everyIdFieldSurvivesProductionJson(String id) throws Exception {
        var builder=new Jackson2ObjectMapperBuilder();
        new com.stcloud.common.config.JacksonConfig().longToStringCustomizer().customize(builder);
        ObjectMapper mapper=builder.build();
        for (Class<?> type : new Class<?>[]{
            com.stcloud.team.dto.TeamSpaceVO.class, com.stcloud.team.dto.TeamRoleVO.class,
            com.stcloud.team.dto.TeamMemberVO.class, com.stcloud.team.dto.TeamInviteVO.class,
            com.stcloud.team.dto.FolderPermissionVO.class, com.stcloud.core.dto.FileNodeVO.class,
            com.stcloud.core.dto.FileTreeNodeVO.class, com.stcloud.core.dto.FileVersionVO.class,
            com.stcloud.share.dto.ShareVO.class}) {
            Object dto=type.getDeclaredConstructor().newInstance(); int checked=0;
            for(Field field:type.getDeclaredFields()) {
                String name=field.getName();
                if(name.equals("id") || name.endsWith("Id") || name.equals("role")) {
                    field.setAccessible(true);
                    if(field.getType()==Long.class) field.set(dto,Long.parseLong(id));
                    else if(field.getType()==String.class) field.set(dto,id);
                    else fail(type.getSimpleName()+"."+name+" 非精确ID类型");
                    checked++;
                }
            }
            assertTrue(checked>0);
            var json=mapper.readTree(mapper.writeValueAsBytes(dto));
            for(Field field:type.getDeclaredFields()) {
                String name=field.getName();
                if(name.equals("id") || name.endsWith("Id") || name.equals("role")) {
                    assertTrue(json.path(name).isTextual(),type.getSimpleName()+"."+name);
                    assertEquals(id,json.path(name).textValue());
                }
            }
        }
        // 请求DTO由Jackson按long精确读取，role额外使用实际严格反序列化器。
        var direct=mapper.readValue("{\"userId\":\""+id+"\",\"role\":\""+id+"\"}",com.stcloud.team.dto.InviteMemberRequest.class);
        assertEquals(id,direct.getUserId().toString()); assertEquals(id,direct.getRole().toString());
        var invite=mapper.readValue("{\"role\":\""+id+"\"}",com.stcloud.team.dto.CreateInviteRequest.class);
        assertEquals(id,invite.getRole().toString());
    }
}
