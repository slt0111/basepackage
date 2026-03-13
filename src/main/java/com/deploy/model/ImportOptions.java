package com.deploy.model;

import lombok.Data;

import java.util.List;

/**
 * 模拟数据导入选项
 * 说明：与设计文档中的 options 对应，用于控制导入范围与策略。
 */
@Data
public class ImportOptions {

    /**
     * 要导入的 schema 列表（与导出包内 manifest/export-report 一致）
     */
    private List<String> schemas;

    /**
     * 要导入的对象清单（可选）；为空或 null 表示导入预览中的全部对象
     * 每项需包含 schema、type、name
     */
    private List<DmObjectItem> objectSelections;

    /**
     * 导入模式：结构+数据、仅结构、仅数据
     */
    private DdlMode ddlMode = DdlMode.STRUCTURE_AND_DATA;

    /**
     * 目标库中对象已存在时的策略：跳过、先删后建
     */
    private WhenExists whenExists = WhenExists.SKIP;

    /**
     * 导入内容模式枚举
     */
    public enum DdlMode {
        /** 执行 DDL 并导入表数据（XML） */
        STRUCTURE_AND_DATA,
        /** 仅执行 DDL，不导入表数据 */
        ONLY_STRUCTURE,
        /** 仅导入表数据，假定表已存在 */
        ONLY_DATA
    }

    /**
     * 对象已存在时的处理策略
     */
    public enum WhenExists {
        /** 已存在则跳过该对象 */
        SKIP,
        /** 先 DROP 再执行 DDL（表/视图等） */
        DROP_AND_RECREATE
    }
}
