package com.deploy.model;

import lombok.Data;

/**
 * 达梦对象条目
 * 说明：用于向前端返回“导出对象清单”，以及前端提交用户选择的对象列表。
 */
@Data
public class DmObjectItem {

    /**
     * 所属 schema（用户模式）
     */
    private String schema;

    /**
     * 对象名称（表名/视图名/序列名等）
     */
    private String name;

    /**
     * 对象类型（TABLE/VIEW/SEQUENCE/SYNONYM/PROCEDURE/FUNCTION/TRIGGER）
     * 说明：表需要导数据 XML，其余对象只导 DDL。
     */
    private String type;
}

