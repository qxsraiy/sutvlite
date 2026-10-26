package top.yogiczy.mytv.core.data.network

import android.annotation.SuppressLint
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object TrustAllSSLSocketFactory {
    @SuppressLint("CustomX509TrustManager")
    val trustManager = object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return arrayOf()
        }
    }

    val sslSocketFactory by lazy {
        // 探针确认: 盒子 (Android 4.4) 必须强制 TLS1.2, 默认协议可能协商成 TLS1.0
        val sslContext = try {
            SSLContext.getInstance("TLSv1.2")
        } catch (_: Throwable) {
            SSLContext.getInstance("TLS")
        }
        sslContext.init(null, arrayOf(trustManager), null)
        sslContext.socketFactory!!
    }

    private fun init() {
        // 关键: 盒子(Android 4.4)平台套接字只报 [SSLv3, TLSv1]。
        // ExoPlayer 点播(m3u8) 与 Glide 图片都走 HttpsURLConnection,
        // 必须包一层强制 TLS1.2, 否则协商成 TLS1.0 会被现代服务器拒绝(SSLProtocolException)。
        val factory = try {
            top.yogiczy.sutvlite.backend.ForceTls12SocketFactory(sslSocketFactory)
        } catch (_: Throwable) {
            sslSocketFactory
        }
        HttpsURLConnection.setDefaultSSLSocketFactory(factory)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    }

    init {
        init()
    }
}