package com.deploy.util;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.InetSocketAddress;

/**
 * 端口检测工具类
 */
public class PortUtil {

    /**
     * 检测指定端口是否被占用
     * @param host 主机地址
     * @param port 端口号
     * @return true表示端口被占用，false表示端口可用
     */
    public static boolean isPortInUse(String host, int port) {
        try (Socket socket = new Socket()) {
            SocketAddress address = new InetSocketAddress(host, port);
            socket.connect(address, 1000); // 1秒超时
            return true; // 连接成功，说明端口被占用
        } catch (IOException e) {
            return false; // 连接失败，说明端口可用
        }
    }

    /**
     * 检测指定端口是否被占用（默认localhost）
     * @param port 端口号
     * @return true表示端口被占用，false表示端口可用
     */
    public static boolean isPortInUse(int port) {
        return isPortInUse("localhost", port);
    }

    /**
     * 检测服务是否启动成功（通过端口检测）
     * @param host 主机地址
     * @param port 端口号
     * @param maxRetries 最大重试次数
     * @param retryInterval 重试间隔（毫秒）
     * @return true表示服务已启动，false表示服务未启动
     */
    public static boolean checkServiceStarted(String host, int port, int maxRetries, long retryInterval) {
        for (int i = 0; i < maxRetries; i++) {
            if (isPortInUse(host, port)) {
                return true;
            }
            try {
                Thread.sleep(retryInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 检测服务是否启动成功（默认参数）
     * @param port 端口号
     * @return true表示服务已启动，false表示服务未启动
     */
    public static boolean checkServiceStarted(int port) {
        // 默认重试10次，每次间隔1秒
        return checkServiceStarted("localhost", port, 10, 1000);
    }
}

