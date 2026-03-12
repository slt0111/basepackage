package com.deploy.model;

import lombok.Data;

/**
 * 模拟数据导出任务状态模型
 * 说明：用于在前端轮询/展示导出进度与下载信息（不在内存中保存敏感数据库密码）。
 */
@Data
public class MockExportJobStatus {

    /**
     * 任务状态枚举
     * 说明：用字符串化的枚举值便于前端展示与判断。
     */
    public enum Status {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }

    /**
     * 任务 ID（UUID）
     */
    private String jobId;

    /**
     * 当前状态
     */
    private Status status;

    /**
     * 状态说明（可用于前端 toast / 页面提示）
     */
    private String message;

    /**
     * 是否已生成可下载的 zip
     */
    private boolean zipReady;

    /**
     * zip 下载文件名（仅文件名）
     */
    private String zipFileName;

    /**
     * zip 物理路径（后端内部使用，前端不需要依赖）
     */
    private String zipFilePath;

    /**
     * 任务开始时间（毫秒）
     */
    private long startedAt;

    /**
     * 任务结束时间（毫秒）
     */
    private long finishedAt;

    /**
     * 导出对象总数
     * 说明：用于前端展示导出进度条的百分比。
     */
    private int totalObjects;

    /**
     * 已完成导出的对象数量
     * 说明：每处理完一个对象（无论成功或失败）都会累加一次，配合 totalObjects 计算完成百分比。
     */
    private int completedObjects;

    /**
     * 导出 zip 文件大小（字节）
     * 说明：任务完成后由后端写入，供前端展示导出结果摘要。
     */
    private long zipSizeBytes;

    /**
     * 导出内容摘要
     * 说明：例如“模式数/对象数/表数/总行数”，便于前端在进度区域展示简要描述。
     */
    private String summary;
}

