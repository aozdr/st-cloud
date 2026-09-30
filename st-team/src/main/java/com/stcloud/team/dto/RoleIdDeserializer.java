package com.stcloud.team.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;

/** 自定义角色 ID 必须以十进制字符串传入，避免 JS 数字精度丢失。 */
public class RoleIdDeserializer extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NUMBER_INT) {
            long value = parser.getLongValue();
            if (value >= 0 && value <= 2) return value;
            throw JsonMappingException.from(parser, "自定义角色 ID 必须使用字符串");
        }
        if (token == JsonToken.VALUE_STRING) {
            String value = parser.getText();
            if (!value.matches("0|[1-9][0-9]*")) throw JsonMappingException.from(parser, "角色 ID 格式无效");
            try { return Long.parseLong(value); }
            catch (NumberFormatException e) { throw JsonMappingException.from(parser, "角色 ID 超出范围", e); }
        }
        throw JsonMappingException.from(parser, "角色 ID 类型无效");
    }
}
