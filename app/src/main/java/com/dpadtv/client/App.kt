package com.dpadtv.client

import android.os.Build
import androidx.multidex.MultiDexApplication
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/**
 * 老盒子（4.4 / 5.0）的启动兼容层。
 *
 * 1. legacy multidex：minSdk<21 时方法数超 64K 必须靠 MultiDex 装第二个 dex，
 *    所以 Application 继承 MultiDexApplication（不能只靠 AGP 自动注入，因为这里显式声明了 android:name）。
 * 2. TLS 1.2：Android 4.4 的 HttpsURLConnection 默认只协商 TLS 1.0，GitHub 这类只收
 *    TLS 1.2+ 的服务会直接握手失败（自更新检查、https 源都会连不上）。系统其实带了
 *    TLS 1.2 实现，只是没默认打开，这里把默认 SSLSocketFactory 换成它。
 *    Api 里的 HTTP 请求和 ExoPlayer 的分片下载都取这个默认值，所以一处生效。
 */
class App : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT in 16..20) {
            runCatching {
                val ctx = SSLContext.getInstance("TLSv1.2")
                ctx.init(null, null, null)
                HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
            }
        }
    }
}
