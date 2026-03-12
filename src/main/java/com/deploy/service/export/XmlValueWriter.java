package com.deploy.service.export;

import java.io.IOException;
import java.io.Writer;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Timestamp;
import java.util.Base64;

/**
 * XML 值写入工具
 * 说明：用于将 ResultSet 的列值以安全的 XML 形式写入，重点处理 CLOB/TEXT 与 BLOB 等特殊字段。
 */
public class XmlValueWriter {

    /**
     * 将字符串做 XML 转义
     * 说明：仅用于非 CDATA 场景；CDATA 内仅需要处理 "]]>" 终止符。
     */
    public static String escapeXml(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 写入 CDATA 内容（处理 "]]>"）
     * 说明：XML 的 CDATA 不能包含 "]]>"，这里采用分段拼接的方式安全输出。
     */
    public static void writeCdata(Writer w, String text) throws IOException {
        if (text == null) {
            return;
        }
        // 将 "]]>" 拆成 "]]]]><![CDATA[>"，避免终止 CDATA
        String safe = text.replace("]]>", "]]]]><![CDATA[>");
        w.write("<![CDATA[");
        w.write(safe);
        w.write("]]>");
    }

    /**
     * 将任意对象值写成 XML 的列值（含特殊类型处理）
     * 说明：调用方负责写入 <col> 的起止标签；本方法只写入列值内容。
     */
    public static void writeValueContent(Writer w, Object value) throws Exception {
        if (value == null) {
            return;
        }
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            String text = clob.getSubString(1, (int) clob.length());
            writeCdata(w, text);
            return;
        }
        if (value instanceof Blob) {
            Blob blob = (Blob) value;
            byte[] bytes = blob.getBytes(1, (int) blob.length());
            w.write(Base64.getEncoder().encodeToString(bytes));
            return;
        }
        if (value instanceof byte[]) {
            w.write(Base64.getEncoder().encodeToString((byte[]) value));
            return;
        }
        if (value instanceof Timestamp) {
            // 说明：时间类统一使用标准字符串，便于解析还原
            w.write(escapeXml(value.toString()));
            return;
        }
        // 兜底：使用 toString 并做 XML 转义
        w.write(escapeXml(String.valueOf(value)));
    }
}

