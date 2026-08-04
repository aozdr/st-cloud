package com.stcloud.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解 - 标注在Controller方法上，自动记录操作日志
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /**
     * 操作类型：UPLOAD/DOWNLOAD/DELETE/SHARE/TEAM_CREATE 等
     */
    String action();

    /**
     * 目标类型：FILE/FOLDER/SHARE/TEAM/USER
     */
    String targetType() default "FILE";

    /**
     * 人工描述（可选），留空则自动从参数生成 JSON 摘要
     */
    String detail() default "";

    /**
     * 指定参数名作为 targetId（可选），如 "fileId"、"userId"
     */
    String targetIdParam() default "";

    /**
     * 指定参数名作为 targetName（可选），如 "folderName"、"newName"
     */
    String targetNameParam() default "";
}
