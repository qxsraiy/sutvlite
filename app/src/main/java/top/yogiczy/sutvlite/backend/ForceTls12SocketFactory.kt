package top.yogiczy.sutvlite.backend

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 强制 TLSv1.2 的 SSLSocket 包装 (针对 海思Hi3798M + Android4.4):
 * 盒子平台套接字 getSupportedProtocols() 只报 [SSLv3, TLSv1],
 * 导致 OkHttp 的 ConnectionSpec 交集只选 TLSv1 -> 服务器拒绝。
 * 但探针实测 setEnabledProtocols(["TLSv1.2"]) 在盒子上握手成功。
 * 这里让套接字"报告"支持 TLSv1.2/1.1/1.0, 并在创建时强制启用,
 * 骗过 OkHttp 的协议过滤, 使 TLSv1.2 被协商。
 */
class ForceTls12Socket(delegate: SSLSocket) : SSLSocket() {
    private val d = delegate
    private val protocols = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1")

    init {
        try { d.setEnabledProtocols(protocols) } catch (_: Throwable) { }
    }

    override fun getSupportedProtocols(): Array<String> = protocols
    override fun getEnabledProtocols(): Array<String> = protocols
    override fun setEnabledProtocols(p: Array<String>) {
        try { d.setEnabledProtocols(p) } catch (_: Throwable) { }
    }

    override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = d.enabledCipherSuites
    override fun setEnabledCipherSuites(s: Array<String>) = d.setEnabledCipherSuites(s)

    override fun getSession(): SSLSession = d.session
    override fun startHandshake() = d.startHandshake()
    override fun addHandshakeCompletedListener(l: javax.net.ssl.HandshakeCompletedListener) = d.addHandshakeCompletedListener(l)
    override fun removeHandshakeCompletedListener(l: javax.net.ssl.HandshakeCompletedListener) = d.removeHandshakeCompletedListener(l)
    override fun setUseClientMode(b: Boolean) { d.useClientMode = b }
    override fun getUseClientMode(): Boolean = d.useClientMode
    override fun setNeedClientAuth(b: Boolean) { d.needClientAuth = b }
    override fun getNeedClientAuth(): Boolean = d.needClientAuth
    override fun setWantClientAuth(b: Boolean) { d.wantClientAuth = b }
    override fun getWantClientAuth(): Boolean = d.wantClientAuth
    override fun setEnableSessionCreation(b: Boolean) { d.enableSessionCreation = b }
    override fun getEnableSessionCreation(): Boolean = d.enableSessionCreation

    // ---- Socket 代理 ----
    override fun connect(addr: SocketAddress) = d.connect(addr)
    override fun connect(addr: SocketAddress, timeout: Int) = d.connect(addr, timeout)
    override fun bind(a: SocketAddress) = d.bind(a)
    override fun close() = d.close()
    override fun getInetAddress(): InetAddress = d.inetAddress
    override fun getLocalAddress(): InetAddress = d.localAddress
    override fun getPort(): Int = d.port
    override fun getLocalPort(): Int = d.localPort
    override fun getRemoteSocketAddress(): SocketAddress = d.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress = d.localSocketAddress
    override fun getInputStream(): InputStream = d.getInputStream()
    override fun getOutputStream(): OutputStream = d.getOutputStream()
    override fun setTcpNoDelay(b: Boolean) { d.tcpNoDelay = b }
    override fun getTcpNoDelay(): Boolean = d.tcpNoDelay
    override fun setSoLinger(b: Boolean, l: Int) = d.setSoLinger(b, l)
    override fun getSoLinger(): Int = d.soLinger
    override fun sendUrgentData(b: Int) = d.sendUrgentData(b)
    override fun setOOBInline(b: Boolean) { d.oobInline = b }
    override fun getOOBInline(): Boolean = d.oobInline
    override fun setSoTimeout(t: Int) { d.soTimeout = t }
    override fun getSoTimeout(): Int = d.soTimeout
    override fun setSendBufferSize(s: Int) { d.sendBufferSize = s }
    override fun getSendBufferSize(): Int = d.sendBufferSize
    override fun setReceiveBufferSize(s: Int) { d.receiveBufferSize = s }
    override fun getReceiveBufferSize(): Int = d.receiveBufferSize
    override fun setKeepAlive(b: Boolean) { d.keepAlive = b }
    override fun getKeepAlive(): Boolean = d.keepAlive
    override fun setTrafficClass(tc: Int) { d.trafficClass = tc }
    override fun getTrafficClass(): Int = d.trafficClass
    override fun setReuseAddress(b: Boolean) { d.reuseAddress = b }
    override fun getReuseAddress(): Boolean = d.reuseAddress
    override fun shutdownInput() = d.shutdownInput()
    override fun shutdownOutput() = d.shutdownOutput()
    override fun toString(): String = d.toString()
    override fun isConnected(): Boolean = d.isConnected
    override fun isClosed(): Boolean = d.isClosed
    override fun isBound(): Boolean = d.isBound
    override fun isInputShutdown(): Boolean = d.isInputShutdown
    override fun isOutputShutdown(): Boolean = d.isOutputShutdown

    // Java7+ SSLSocket 方法 (Android 4.4 存在)
    override fun getSSLParameters(): SSLParameters = d.sslParameters
    override fun setSSLParameters(p: SSLParameters) { d.sslParameters = p }
}

/** 包装 SSLSocketFactory: 返回的套接字强制报告并启用 TLSv1.2 */
class ForceTls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, h: String, p: Int, autoClose: Boolean): Socket =
        wrap(delegate.createSocket(s, h, p, autoClose))
    override fun createSocket(h: String, p: Int): Socket = wrap(delegate.createSocket(h, p))
    override fun createSocket(h: String, p: Int, lh: InetAddress, lp: Int): Socket =
        wrap(delegate.createSocket(h, p, lh, lp))
    override fun createSocket(a: InetAddress, p: Int): Socket = wrap(delegate.createSocket(a, p))
    override fun createSocket(a: InetAddress, p: Int, lh: InetAddress, lp: Int): Socket =
        wrap(delegate.createSocket(a, p, lh, lp))

    private fun wrap(s: Socket): Socket =
        if (s is SSLSocket) ForceTls12Socket(s) else s
}