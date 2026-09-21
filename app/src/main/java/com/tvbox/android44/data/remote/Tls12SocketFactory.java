package com.tvbox.android44.data.remote;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * API 19~21 上显式启用 TLSv1.2（Android 4.x 默认关闭该协议，导致对仅支持
 * TLS1.2+ 的现代服务握手失败）。只启用协议，不改信任策略，不禁用校验。
 * 标准 OkHttp 兼容方案（文档 02 §7）。
 */
public final class Tls12SocketFactory extends SSLSocketFactory {

    private static final String[] TLS12 = {"TLSv1.2"};

    private final SSLSocketFactory delegate;

    private Tls12SocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    /** API<22 时返回启用 TLS1.2 的工厂；否则返回 null（走系统默认）。 */
    public static SSLSocketFactory createIfNecessary(int sdkInt) {
        if (sdkInt < 22) {
            try {
                SSLContext context = SSLContext.getInstance("TLSv1.2");
                context.init(null, null, null);
                return new Tls12SocketFactory(context.getSocketFactory());
            } catch (Exception e) {
                return null; // 初始化失败回退系统默认
            }
        }
        return null;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose)
            throws IOException {
        return enableTls12(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port)
            throws IOException, UnknownHostException {
        return enableTls12(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException, UnknownHostException {
        return enableTls12(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enableTls12(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                               int localPort) throws IOException {
        return enableTls12(delegate.createSocket(address, port, localAddress, localPort));
    }

    private static Socket enableTls12(Socket socket) {
        if (socket instanceof SSLSocket) {
            ((SSLSocket) socket).setEnabledProtocols(TLS12);
        }
        return socket;
    }
}
