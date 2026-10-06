package com.tvbox.android44.common;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * 统一错误分类：面向用户的中文提示 + 内部错误类别。
 * 绝不把域名、Key、完整异常信息展示给普通用户。
 */
public enum ErrorKind {
    NO_NETWORK,
    DNS,
    TLS,
    TIMEOUT,
    HTTP,
    EMPTY_BODY,
    PARSE,
    UNSUPPORTED_DEVICE,
    PLAYBACK,
    PERMISSION,
    CANCELLED,
    OTHER;

    public String userMessage() {
        switch (this) {
            case NO_NETWORK:
                return "网络未连接，请检查网络后重试";
            case DNS:
            case TLS:
                return "无法访问服务，请稍后重试或更换视频接口";
            case TIMEOUT:
                return "请求超时，请稍后重试";
            case HTTP:
                return "服务暂时不可用，请稍后重试";
            case EMPTY_BODY:
                return "服务返回为空，请稍后重试";
            case PARSE:
                return "数据格式异常，请稍后重试";
            case UNSUPPORTED_DEVICE:
                return "当前设备不支持该格式，请尝试其他线路";
            case PLAYBACK:
                return "播放失败，请尝试其他线路";
            case PERMISSION:
                return "缺少必要权限，请在设置中开启";
            case CANCELLED:
                return "已取消";
            default:
                return "操作失败，请稍后重试";
        }
    }

    /** 把 IO 异常映射为错误类别；不吞掉根因，但用户只看分类提示。 */
    public static ErrorKind fromException(Throwable t) {
        if (t instanceof java.net.SocketTimeoutException) return TIMEOUT;
        // OkHttp's overall call deadline reports InterruptedIOException("timeout").
        if (t instanceof java.io.InterruptedIOException && "timeout".equals(t.getMessage())) return TIMEOUT;
        if (t instanceof UnknownHostException) return DNS;
        if (t instanceof SSLException) return TLS;
        if (t instanceof ConnectException) return HTTP;
        if (t instanceof IOException && t.getMessage() != null && t.getMessage().startsWith("HTTP ")) return HTTP;
        if (t instanceof IOException) return OTHER;
        return OTHER;
    }
}
