package com.dpadtv.client

import android.os.Build
import androidx.multidex.MultiDexApplication
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 老盒子（4.4 / 5.0）的启动兼容层。
 *
 * 1. legacy multidex：minSdk<21 时方法数超 64K 必须靠 MultiDex 装第二个 dex，
 *    所以 Application 继承 MultiDexApplication（不能只靠 AGP 自动注入，因为这里显式声明了 android:name）。
 * 2. TLS 1.2：Android 4.4 的默认 socket 只启用 TLS 1.0，GitHub 这类只收 1.2+ 的服务会直接握手失败
 *    （自更新检查、https 源都连不上）。系统其实带了 1.2 实现，只是没默认打开。
 *    打开的方式只有一种是对的：**在已有 socket 上把 supportedProtocols 里除 SSLv3 全塞回 enabledProtocols**。
 *    网上流传的另一种 —— 换掉默认工厂，用 SSLContext.getInstance("TLSv1.2") 的工厂 —— 会把
 *    enabled 缩成"只有 1.2"，Conscrypt 于是给 SSL_CTX 设上 SSL_OP_NO_SSLv3|NO_TLSv1|NO_TLSv1_1。
 *    后果在 4.4 的 openssl/ssl/s23_clnt.c:714：它先按**记录头的 minor 字节**挑方法，
 *    命中不了就报 SSL_R_UNSUPPORTED_PROTOCOL。服务器答 TLS1.0/1.1、或者干脆回一条记录头是 03 01 的
 *    fatal alert，全都会撞进这一行，错误信息只剩"unsupported protocol"，真实原因被吃掉。
 *    Api 里的 HTTP 请求和 ExoPlayer 的分片下载都取这个默认值，所以一处生效。
 */
class App : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT in 16..20) {
            runCatching {
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, null, null)
                HttpsURLConnection.setDefaultSSLSocketFactory(AllTlsVersions(ctx.socketFactory))
            }
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
                    // SSLv3 不收着：POODLE，而且开着它会让上面的版本判定多一条能撞错的分支
                    val keep = socket.supportedProtocols.filter { it != "SSLv3" }
                    if (keep.isNotEmpty()) socket.enabledProtocols = keep.toTypedArray()
                }
            }
            return socket
        }
    }
}
