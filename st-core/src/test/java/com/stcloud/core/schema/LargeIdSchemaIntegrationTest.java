package com.stcloud.core.schema;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只执行仓库测试DDL，独立H2；不连接开发MySQL。 */
class LargeIdSchemaIntegrationTest {
    static final String[] IDS = {"9007199254740993", "9007199254740995", "9223372036854775806"};
    @ParameterizedTest @ValueSource(strings={"st-core", "st-auth", "st-team"})
    void actualSchemaStoresExactIdsAndSecurityVersion(String module) throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).getParent();
        String sql=Files.readString(root.resolve(module+"/src/test/resources/schema.sql"));
        try(var db=DriverManager.getConnection("jdbc:h2:mem:bigids-"+module+";MODE=MySQL", "sa", "")) {
            for(String table : new String[]{"sys_user", "team_member", "team_invite"}) {
                var matcher=Pattern.compile("CREATE TABLE IF NOT EXISTS "+table+" \\([\\s\\S]*?\n\\);", Pattern.CASE_INSENSITIVE).matcher(sql);
                if(!matcher.find()) { assertNotEquals("sys_user",table); continue; }
                db.createStatement().execute(matcher.group());
                String column=table.equals("sys_user")?"security_version":"role";
                try(var meta=db.getMetaData().getColumns(null,null,table.toUpperCase(),column.toUpperCase())) {
                    assertTrue(meta.next()); assertEquals(Types.BIGINT,meta.getInt("DATA_TYPE"));
                    assertEquals(DatabaseMetaData.columnNoNulls,meta.getInt("NULLABLE"));
                    assertEquals(table.equals("sys_user")?"0":"2",meta.getString("COLUMN_DEF"));
                }
                for(String id:IDS) {
                    String insert=table.equals("sys_user")?"INSERT INTO sys_user(id,tenant_id,username,password,security_version) VALUES(?,1,?, 'fixture',?)"
                        :table.equals("team_member")?"INSERT INTO team_member(id,tenant_id,space_id,user_id,role) VALUES(?,1,1,?,?)"
                        :"INSERT INTO team_invite(id,tenant_id,space_id,invite_code,created_by,role) VALUES(?,1,1,?,1,?)";
                    try(var stmt=db.prepareStatement(insert)) {
                        stmt.setLong(1,Long.parseLong(id));stmt.setString(2,id);stmt.setLong(3,Long.parseLong(id));assertEquals(1,stmt.executeUpdate());
                    }
                    try(var row=db.createStatement().executeQuery("SELECT id,"+column+" FROM "+table+" WHERE id="+id)) {
                        assertTrue(row.next());assertEquals(id,row.getString(1));assertEquals(id,row.getString(2));
                    }
                }
                if(table.equals("sys_user")) {
                    assertEquals(1,db.createStatement().executeUpdate("UPDATE sys_user SET security_version=security_version+1 WHERE id="+IDS[2]));
                    try(var row=db.createStatement().executeQuery("SELECT security_version FROM sys_user WHERE id="+IDS[2])) {
                        assertTrue(row.next());assertEquals("9223372036854775807",row.getString(1));
                    }
                }
            }
        }
    }
    static class LegacyRole { public Integer role; }
    @Test void integerCompatibilityFailsClosedForLargeCustomRoles() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        for(int role=0;role<3;role++) {
            assertEquals(role,mapper.readValue("{\"role\":"+role+"}",LegacyRole.class).role);
            assertEquals(role,mapper.readValue("{\"role\":\""+role+"\"}",LegacyRole.class).role);
        }
        for(String id:IDS) {
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->mapper.readValue("{\"role\":"+id+"}",LegacyRole.class));
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->mapper.readValue("{\"role\":\""+id+"\"}",LegacyRole.class));
            assertThrows(ArithmeticException.class,()->Math.toIntExact(Long.parseLong(id)));
        }
    }
}
