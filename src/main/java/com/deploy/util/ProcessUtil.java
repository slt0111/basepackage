package com.deploy.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 进程管理工具类
 */
public class ProcessUtil {

    /**
     * 启动外部进程
     * @param command 命令（Windows使用cmd /c，Linux使用sh）
     * @param workingDir 工作目录
     * @param env 环境变量
     * @return Process对象
     * @throws IOException IO异常
     */
    public static Process startProcess(String command, String workingDir, Map<String, String> env) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder();
        
        // 根据操作系统选择命令前缀
        String os = System.getProperty("os.name").toLowerCase();
        List<String> commands = new ArrayList<>();
        
        if (os.contains("win")) {
            // Windows系统
            commands.add("cmd");
            commands.add("/c");
            commands.add(command);
        } else {
            // Linux系统
            commands.add("sh");
            commands.add("-c");
            commands.add(command);
        }
        
        processBuilder.command(commands);
        
        // 设置工作目录
        if (workingDir != null && !workingDir.isEmpty()) {
            processBuilder.directory(new File(workingDir));
        }
        
        // 设置环境变量
        if (env != null) {
            Map<String, String> processEnv = processBuilder.environment();
            processEnv.putAll(env);
        }
        
        // 重定向错误输出到标准输出
        processBuilder.redirectErrorStream(true);
        
        return processBuilder.start();
    }

    /**
     * 启动外部进程（简化版）
     * @param command 命令
     * @param workingDir 工作目录
     * @return Process对象
     * @throws IOException IO异常
     */
    public static Process startProcess(String command, String workingDir) throws IOException {
        return startProcess(command, workingDir, null);
    }

    /**
     * 读取进程输出（阻塞直到进程结束）
     * @param process 进程对象
     * @return 输出内容列表
     * @throws IOException IO异常
     */
    public static List<String> readProcessOutput(Process process) throws IOException {
        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }
        return output;
    }

    /**
     * 检测进程是否正在运行
     * @param process 进程对象
     * @return true表示正在运行，false表示已结束
     */
    public static boolean isProcessRunning(Process process) {
        try {
            process.exitValue();
            return false; // 进程已结束
        } catch (IllegalThreadStateException e) {
            return true; // 进程仍在运行
        }
    }

    /**
     * 等待进程结束
     * @param process 进程对象
     * @param timeout 超时时间（毫秒），-1表示无限等待
     * @return true表示进程正常结束，false表示超时或异常
     */
    public static boolean waitForProcess(Process process, long timeout) {
        try {
            if (timeout < 0) {
                process.waitFor();
                return true;
            } else {
                boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
                return finished;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 强制终止进程
     * @param process 进程对象
     */
    public static void destroyProcess(Process process) {
        if (process != null && isProcessRunning(process)) {
            process.destroyForcibly();
        }
    }
}

