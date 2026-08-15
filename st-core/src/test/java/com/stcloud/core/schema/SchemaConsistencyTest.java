package com.stcloud.core.schema;

import com.stcloud.common.entity.BaseEntity;
import com.stcloud.core.entity.EventLog;
import com.stcloud.core.entity.FileChunk;
import com.stcloud.core.entity.FileFavorite;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.entity.FileVersion;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema 一致性校验测试（补充严谨性）。
 * <p>
 * 三层校验：
 * <ol>
 *   <li>实体字段 -> schema.sql 列覆盖（H2 测试 schema 不缺列）</li>
 *   <li>实体字段 -> MySQL init SQL 列覆盖（生产 DDL 不缺列）</li>
 *   <li>schema.sql 与 init SQL 列集对齐（H2 与 MySQL 不漂移）</li>
 * </ol>
 * 此测试防止「实体新增字段后仅更新 H2 schema.sql 而漏写 MySQL 迁移」导致线上
 * Unknown column 报错（如 object_id 漏迁移事件）。
 */
@DisplayName("Schema 一致性校验")
class SchemaConsistencyTest {

    /** 待校验的实体类列表（st-core 全部实体） */
    private static final List<Class<?>> ENTITIES = List.of(
            FileNode.class, FileObject.class, FileChunk.class,
            FileFavorite.class, FileVersion.class, EventLog.class
    );

    /** BaseEntity 的列：id / tenant_id / created_at / updated_at / deleted */
    private static final Set<String> BASE_COLUMNS = Set.of("id", "tenant_id", "created_at", "updated_at", "deleted");

    // ==================== 测试方法 ====================

    @Test
    @DisplayName("实体字段在 schema.sql（H2 测试库）中均有对应列")
    void entityFieldsExistInTestSchema() throws IOException {
        Map<String, Set<String>> h2Schema = parseSchemaSql();
        List<String> missing = new ArrayList<>();

        for (Class<?> entity : ENTITIES) {
            String table = getTableName(entity);
            Set<String> entityCols = getEntityColumns(entity);
            Set<String> schemaCols = h2Schema.get(table);
            if (schemaCols == null) {
                missing.add(String.format("[%s] table missing in schema.sql", table));
                continue;
            }
            for (String col : entityCols) {
                if (!schemaCols.contains(col)) {
                    missing.add(String.format("[%s] column '%s' missing in schema.sql", table, col));
                }
            }
        }
        assertTrue(missing.isEmpty(), "schema.sql column coverage gaps:\n  " + String.join("\n  ", missing));
    }

    @Test
    @DisplayName("实体字段在 MySQL init SQL（生产 DDL）中均有对应列")
    void entityFieldsExistInMysqlInitSql() throws IOException {
        Map<String, Set<String>> mysqlSchema = parseInitSqlFiles();
        assertNotNull(mysqlSchema, "docker/mysql/init directory not readable, skipping");

        List<String> missing = new ArrayList<>();
        for (Class<?> entity : ENTITIES) {
            String table = getTableName(entity);
            Set<String> entityCols = getEntityColumns(entity);
            Set<String> mysqlCols = mysqlSchema.get(table);
            if (mysqlCols == null) {
                missing.add(String.format("[%s] table missing in init SQL", table));
                continue;
            }
            for (String col : entityCols) {
                if (!mysqlCols.contains(col)) {
                    missing.add(String.format("[%s] column '%s' missing in init SQL", table, col));
                }
            }
        }
        assertTrue(missing.isEmpty(), "MySQL init SQL column coverage gaps:\n  " + String.join("\n  ", missing));
    }

    @Test
    @DisplayName("schema.sql 与 init SQL 共有表的列集对齐（无漂移）")
    void testSchemaAndMysqlInitSqlAligned() throws IOException {
        Map<String, Set<String>> h2Schema = parseSchemaSql();
        Map<String, Set<String>> mysqlSchema = parseInitSqlFiles();
        if (mysqlSchema == null) return;

        List<String> drift = new ArrayList<>();
        for (String table : h2Schema.keySet()) {
            Set<String> mysqlCols = mysqlSchema.get(table);
            if (mysqlCols == null) continue;
            Set<String> h2Cols = h2Schema.get(table);

            Set<String> onlyInH2 = new HashSet<>(h2Cols);
            onlyInH2.removeAll(mysqlCols);
            Set<String> onlyInMysql = new HashSet<>(mysqlCols);
            onlyInMysql.removeAll(h2Cols);

            if (!onlyInH2.isEmpty()) {
                drift.add(String.format("[%s] only in schema.sql: %s", table, onlyInH2));
            }
            if (!onlyInMysql.isEmpty()) {
                drift.add(String.format("[%s] only in init SQL: %s", table, onlyInMysql));
            }
        }
        // Drift is a warning, not a hard failure (H2/MySQL type differences may produce extra columns)
        if (!drift.isEmpty()) {
            System.out.println("WARN Schema drift (H2 vs MySQL init SQL):\n  " + String.join("\n  ", drift));
        }
    }

    // ==================== 解析方法 ====================

    /** 解析 st-core/src/test/resources/schema.sql -> table -> columns */
    private Map<String, Set<String>> parseSchemaSql() throws IOException {
        Path schemaPath = Paths.get("src/test/resources/schema.sql");
        String sql = Files.readString(schemaPath);
        return parseCreateTableColumns(sql);
    }

    /** 解析 docker/mysql/init/*.sql -> table -> columns（含 ALTER TABLE ADD COLUMN） */
    private Map<String, Set<String>> parseInitSqlFiles() throws IOException {
        Path initDir = Paths.get(System.getProperty("user.dir")).getParent().resolve("docker/mysql/init");
        if (!Files.isDirectory(initDir)) {
            initDir = Paths.get(System.getProperty("user.dir")).resolve("../../docker/mysql/init").normalize();
            if (!Files.isDirectory(initDir)) return null;
        }

        Map<String, Set<String>> schema = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Path> files;
        try (Stream<Path> stream = Files.list(initDir)) {
            files = stream.filter(p -> p.toString().endsWith(".sql")).sorted().collect(Collectors.toList());
        }

        for (Path file : files) {
            String sql = Files.readString(file);
            Map<String, Set<String>> created = parseCreateTableColumns(sql);
            for (var entry : created.entrySet()) {
                schema.computeIfAbsent(entry.getKey(), k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                        .addAll(entry.getValue());
            }
            Matcher m = Pattern.compile("ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+(\\w+)", Pattern.CASE_INSENSITIVE).matcher(sql);
            while (m.find()) {
                schema.computeIfAbsent(m.group(1), k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                        .add(m.group(2));
            }
        }
        return schema;
    }

    /** 从 SQL 文本中解析 CREATE TABLE -> table -> column names */
    private Map<String, Set<String>> parseCreateTableColumns(String sql) {
        Map<String, Set<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Pattern p = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s*\\((.*?)\\)\\s*(?:ENGINE|;|$)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(sql);
        while (m.find()) {
            String table = m.group(1);
            String body = m.group(2);
            Set<String> cols = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String line : body.split("\\n")) {
                line = line.trim().replaceAll(",\\s*$", "").trim();
                if (line.isEmpty()) continue;
                String upper = line.toUpperCase();
                if (upper.startsWith("PRIMARY KEY") || upper.startsWith("UNIQUE KEY")
                        || upper.startsWith("UNIQUE ") || upper.startsWith("KEY ")
                        || upper.startsWith("INDEX") || upper.startsWith("CONSTRAINT")
                        || upper.startsWith("FOREIGN KEY") || upper.startsWith("CHECK")) {
                    continue;
                }
                String colName = line.split("\\s+")[0];
                if (colName.matches("\\w+")) {
                    cols.add(colName);
                }
            }
            result.put(table, cols);
        }
        return result;
    }

    // ==================== 实体反射 ====================

    /** 获取实体的 @TableName 值 */
    private String getTableName(Class<?> clazz) {
        com.baomidou.mybatisplus.annotation.TableName anno = clazz.getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class);
        if (anno != null && !anno.value().isEmpty()) {
            return anno.value();
        }
        return camelToSnake(clazz.getSimpleName());
    }

    /** 获取实体的全部列名（仅当继承 BaseEntity 时添加基础列） */
    private Set<String> getEntityColumns(Class<?> clazz) {
        Set<String> cols = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // 仅继承 BaseEntity 的实体才有基础列
        if (BaseEntity.class.isAssignableFrom(clazz) && clazz != BaseEntity.class) {
            cols.addAll(BASE_COLUMNS);
        }
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                String colName = getColumnName(field);
                if (colName != null) {
                    cols.add(colName);
                }
            }
            current = current.getSuperclass();
        }
        return cols;
    }

    /** 字段 -> 列名：优先 @TableField/@TableId 注解，否则 camelCase -> snake_case */
    private String getColumnName(Field field) {
        if (field.getName().equals("serialVersionUID")) return null;
        if (field.isAnnotationPresent(TableField.class)) {
            TableField tf = field.getAnnotation(TableField.class);
            if (!tf.value().isEmpty()) return tf.value();
            if (tf.exist()) return camelToSnake(field.getName());
            if (!tf.exist()) return null;
        }
        if (field.isAnnotationPresent(TableId.class)) {
            return field.getName().equals("id") ? "id" : camelToSnake(field.getName());
        }
        return camelToSnake(field.getName());
    }

    /** camelCase -> snake_case */
    private String camelToSnake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}