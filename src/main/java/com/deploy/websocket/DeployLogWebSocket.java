package com.deploy.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket日志推送处理器
 * 用于实时推送部署过程中的日志信息
 */
@Component
public class DeployLogWebSocket extends TextWebSocketHandler {

    /**
     * 存储所有连接的WebSocket会话
     */
    private static final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    /**
     * 连接建立时调用
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        sendMessage("WebSocket连接已建立");
    }

    /**
     * 连接关闭时调用
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
    }

    /**
     * 处理接收到的消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 可以处理客户端发送的消息，这里暂时不需要
    }

    /**
     * 向所有连接的客户端发送消息
     * @param message 消息内容
     */
    public static void sendMessage(String message) {
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                }
            } catch (IOException e) {
                // 发送失败，移除该会话
                sessions.remove(session);
            }
        }
    }

    /**
     * 向所有连接的客户端发送消息（带时间戳）
     * @param message 消息内容
     */
    public static void sendLog(String message) {
        String timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        sendMessage("[" + timestamp + "] " + message);
    }

    /**
     * 获取当前连接的客户端数量
     * @return 连接数
     */
    public static int getConnectionCount() {
        return sessions.size();
    }
}

