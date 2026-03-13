package com.deploy.model;

import lombok.Data;

/**
 * 模拟数据导入任务状态模型
 * 说明：与 MockExportJobStatus 对齐，供前端轮询进度与展示结果。
 */
@Data
public class MockImportJobStatus {

    /**
     * 任务状态枚举
     */
    public enum Status {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }

    /** 任务 ID（UUID） */
    private String jobId;

    /** 当前状态 */
    private Status status;

    /** 状态说明（前端 toast / 页面提示） */
    private String message;

    /** 任务开始时间（毫秒） */
    private long startedAt;

    /** 任务结束时间（毫秒） */
    private long finishedAt;

    /** 导入对象总数（用于进度条） */
    private int totalObjects;

    /** 已处理对象数（成功或失败均计数） */
    private int completedObjects;

    /** 导入结果摘要（如：成功数/失败数/总行数） */
    private String summary;

    /** 上传的 zip 文件名（仅文件名，便于展示） */
    private String zipFileName;
}
