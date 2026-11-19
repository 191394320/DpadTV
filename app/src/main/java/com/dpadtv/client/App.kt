package com.dpadtv.client

import android.content.Context
import android.os.Build
import androidx.multidex.MultiDexApplication
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 老盒子（4.4 / 5.0）的启动兼容层，只管 API 16–20。
 *
 * 1. legacy multidex：minSdk<21 时方法数超 64K 必须靠 MultiDex 装第二个 dex，
 *    所以 Application 继承 MultiDexApplication（不能只靠 AGP 自动注入，因为这里显式声明了 android:name）。
 * 2. 下面两件事都是"2013 年冻结的系统"对"现在的互联网"的缺口，新系统不需要，
 *    所以整个开关按 SDK_INT 挡在 16..20 —— 尤其不能把 TLS 1.0/1.1 重新打开到 21+ 的设备上。
 *    - TLS 版本：4.4 默认只启用 TLS 1.0，GitHub 这类只收 1.2+ 的服务直接握手失败。系统其实带了
 *      1.2 实现，只是没默认打开。打开的正确姿势是**把 supportedProtocols 全塞回 enabledProtocols**，
 *      而不是换成 SSLContext.getInstance("TLSv1.2") 的工厂 —— 后者把 enabled 缩成单版本，
 *      Conscrypt 于是设上 SSL_OP_NO_SSLv3|NO_TLSv1|NO_TLSv1_1，而 4.4 的
 *      openssl/ssl/s23_clnt.c:714 是按**记录头 minor 字节**挑方法的，
 *      服务器降级、或只回一条记录头 03 01 的 fatal alert，都会撞进那行变成
 *      "unsupported protocol"，把真实原因吃掉。
 *    - 根证书：/system/etc/security/cacerts 冻结在 2013 年底，2016 年以后入库的根
 *      （Certum Trusted Root CA、ISRG Root X1、GTS…）在上面一律
 *      "Trust anchor for certification path not found"，同一张证书在新系统上是合法的。
 *      随包带一份当前的 CA 表做兜底，先让系统库判，判不动才用它。
 *    Api 里的 HTTP 请求和 ExoPlayer 的分片下载都取这个默认值，所以一处生效。
 */
class App : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT !in 16..20) return
        runCatching {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(BackupTrust(this)), null)
            HttpsURLConnection.setDefaultSSLSocketFactory(AllTlsVersions(ctx.socketFactory))
        }
    }

    /** 只改 enabledProtocols 的透传工厂。五个 createSocket 重载都得包：
     *  4.4 的 OkHttp 复用连接时走的是 createSocket(Socket, host, port, autoClose) 那一个。 */
    private class AllTlsVersions(private val base: SSLSocketFactory) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

        override fun createSocket(host: String, port: Int): Socket =
            open(base.createSocket(host, port))

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): Socket = open(base.createSocket(host, port, localHost, localPort))

        override fun createSocket(address: InetAddress, port: Int): Socket =
            open(base.createSocket(address, port))

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = open(base.createSocket(address, port, localAddress, localPort))

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            open(base.createSocket(s, host, port, autoClose))

        private fun open(socket: Socket): Socket {
            if (socket is SSLSocket) {
                runCatching {
                    // SSLv3 不收着：POODLE，而且开着它会让 s23_clnt 的版本判定多一条能撞错的分支
                    val keep = socket.supportedProtocols.filter { it != "SSLv3" }
                    if (keep.isNotEmpty()) socket.enabledProtocols = keep.toTypedArray()
                }
            }
            return socket
        }
    }

    /** 系统信任库先判，它说不认时才用随包那份兜底。
     *  兜底是懒加载的：启动不解析 118 张根证书，真撞上不认识的主机才付这个钱。
     *  只兜"找不到锚点"这一类，不去碰主机名校验 —— 那是 HttpsURLConnection 自己做的，没动。 */
    private class BackupTrust(context: Context) : X509TrustManager {

        private val appCtx = context.applicationContext
        private val system = systemTrustManager()
        private var backup: X509TrustManager? = null
        private var backupTried = false

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (systemError: CertificateException) {
                // 兜底库也认不下来时抛它：那句带的是新表的判定，比"trust anchor not found"有指向性
                (backupOrNull() ?: throw systemError).checkServerTrusted(chain, authType)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

        private fun backupOrNull(): X509TrustManager? = synchronized(this) {
            if (!backupTried) {
                backupTried = true
                backup = runCatching { bundledTrustManager() }.getOrNull()
            }
            backup
        }

        private fun systemTrustManager(): X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()

        /** res/raw/cacert.pem 是 curl 分发的 Mozilla 根证书表（同 OkHttp 在老安卓上的做法）。
         *  CertificateFactory 直接吃 PEM 流，不用自己切 BEGIN/END。 */
        private fun bundledTrustManager(): X509TrustManager {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            val certs = appCtx.resources.openRawResource(R.raw.cacert).use {
                CertificateFactory.getInstance("X.509").generateCertificates(it)
            }
            var i = 0
            for (cert in certs) ks.setCertificateEntry("bundled${i++}", cert)
            return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(ks) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
