package top.yogiczy.sutvlite.backend

import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import top.yogiczy.mytv.core.data.network.TrustAllSSLSocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/**
 * 针对 海思Hi3798M + Android4.4 的 TLS 适配 (探针实测方案):
 * 盒子平台套接字 getSupportedProtocols() 只报 [SSLv3, TLSv1],
 * OkHttp 因此只能协商 TLS1.0 被现代服务器拒绝 (tlsv1 alert)。
 * 探针实测: 直接 setEnabledProtocols(["TLSv1.2"]) 握手成功。
 * 方案: ForceTls12SocketFactory 包装平台套接字, 报告+启用 TLSv1.2,
 * 让 OkHttp 协商 TLSv1.2; 不限制密码套件, 对正常主机无回归。
 */
object LegacyTls {
    /** 宽松信任的 SSLSocketFactory (平台 TLS context) */
    private val baseFactory: SSLSocketFactory by lazy {
        val ctx = try {
            SSLContext.getInstance("TLSv1.2")
        } catch (_: Throwable) {
            SSLContext.getInstance("TLS")
        }
        ctx.init(null, arrayOf(TrustAllSSLSocketFactory.trustManager), null)
        ctx.socketFactory
    }

    /** 强制 TLSv1.2 的包装 (报告+启用) */
    val sslSocketFactory: SSLSocketFactory by lazy {
        ForceTls12SocketFactory(baseFactory)
    }

    val CBC_SPEC: ConnectionSpec by lazy {
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
            .cipherSuites(
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
                CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
            )
            .build()
    }

    /** OkHttp 客户端构建器: ForceTls12 + 宽松信任 */
    fun builder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, TrustAllSSLSocketFactory.trustManager)
            .hostnameVerifier { _, _ -> true }
}