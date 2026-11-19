package com.dpadtv.client

import java.net.HttpURLConnection
import java.net.URL

/**
 * 盒子这一侧的直连探测 + 失败归类。
 *
 * 为什么要有它：片源的采集接口全是**服务端代发**的（[Api.testSource] 走 /api/source/test），
 * 那句「可用」只说明"你这台服务器取到源了"，不说明"这块盒子能播"。盒子的 TLS 版本、加密套件、
 * 根证书表都是它自己的，而播放分片又是盒子直连 CDN，所以两个答案可以完全相反：
 * 列表显示可用，播放报 trust anchor。这里补的就是直连那一段，并把失败归到有限的几类墙上，
 * 换到一个没试过的源时一眼看得出撞的是哪类，不用再靠截图猜。
 */
object TlsDiag {

    /** ok=能不能连上；tag=给列表那一格用的四五个字；detail=给弹窗/toast 用的整句；ms=耗时 */
    data class Outcome(val ok: Boolean, val tag: String, val detail: String, val ms: Long)

    /** 只取响应头，不下整个文件。HLS 清单一般 200/206；403/404 说明 TLS 已经通了、是对方不给，
     *  那是另一类问题，不能和握手失败混成一格。 */
    fun probe(url: String, timeoutMs: Int = 6000): Outcome {
        val t0 = System.currentTimeMillis()
        if (url.isEmpty()) return Outcome(false, "没地址", "这个源没有可探测的地址", 0L)
        var conn: HttpURLConnection? = null
        val out = try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Range", "bytes=0-1023")
            conn.setRequestProperty("User-Agent", "DpadTV-Android")
            val code = conn.responseCode
            if (code in 200..399) win(t0) else Outcome(false, "HTTP$code", "TLS 通了，对方返回 HTTP $code", spent(t0))
        } catch (e: Exception) {
            Outcome(false, tag(e), detail(e), spent(t0))
        }
        runCatching { conn?.disconnect() }
        return out
    }

    private fun win(t0: Long) = Outcome(true, "", "", spent(t0))

    private fun spent(t0: Long) = System.currentTimeMillis() - t0

    /** 整条 cause 链拼成一个小写串，分类只看它。Android 会把证书错误包在
     *  SSLHandshakeException 里，所以只看最外层类型会全部归到"握手失败"。 */
    private fun haystack(t: Throwable?): String {
        val sb = StringBuilder()
        var cur = t
        var depth = 0
        while (cur != null && depth < 6) {
            sb.append(cur.javaClass.name.lowercase()).append(' ')
            sb.append(cur.message?.lowercase()).append(' ')
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }

    /** 短句分类，宽度按片源列表那一格来的。顺序有讲究：证书类的判定必须排在
     *  "握手"之前，否则全被 SSLHandshakeException 这个词吸走。 */
    fun tag(t: Throwable?): String {
        val s = haystack(t)
        return when {
            has(s, "trust anchor", "no trusted certificate", "valid certification path") -> "不认证书"
            has(s, "not yet valid", "certificate expired", "has expired", "validity") -> "证书过期"
            has(s, "eddsa", "ed25519", "unrecognized signature", "signature algorithm name") -> "算法太新"
            "hostname" in s -> "主机名不符"
            "certpathvalidator" in s -> "证书链不通"
            "unknownhost" in s -> "解析不了"
            has(s, "timed out", "timeout") -> "超时"
            has(s, "connectexception", "connection refused", "failed to connect", "network is unreachable") -> "连不上"
            has(s, "unsupported protocol", "handshake", "cipher", "protocol_version") -> "TLS谈不拢"
            "ssl" in s -> "TLS失败"
            "ioexception" in s || "eof" in s -> "网络中断"
            else -> t?.javaClass?.simpleName?.take(10) ?: "未知"
        }
    }

    fun detail(t: Throwable?): String {
        val g = tag(t)
        return when (g) {
            "不认证书" -> "本机不认这张证书的根：系统根证书表冻在 2013 年，随包兜底现在的状态是「${App.caStatus}」"
            "证书过期" -> "证书本身过期了，或者盒子时钟不对（盒子断电走时不准很常见）"
            "算法太新" -> "证书用了本机不认识的签名算法（Ed25519 这类要 Android 5 以上）"
            "主机名不符" -> "证书上的域名和地址对不上，源地址写法或对方配置有问题"
            "证书链不通" -> "证书链本身有问题：缺中间证书、被裁剪，或链没闭合"
            "解析不了" -> "域名解析不了：DNS 被污染、盒子没连外网，或这个节点已经下线"
            "超时" -> "连上了但对方不响应，多半是节点被墙或已经挂了"
            "连不上" -> "端口拒绝或节点不可达"
            "TLS谈不拢" -> "TLS 版本或加密套件谈不拢：服务器只收太新的（比如只给 TLS 1.3），4.4 到不了"
            "TLS失败" -> "TLS 握手失败，具体原因看下面的异常链"
            "网络中断" -> "连接被对方断开"
            else -> chain(t)
        }
    }

    /** 异常链：外层截短，**最里层留全**。4.4 的 OpenSSL 错误码只在最后一行
     *  （error:14077410:SSL routines:SSL23_GET_SERVER_HELLO:sslv3 alert handshake
     *  failure 之类），而它才能分清是服务器拒绝我们的 ClientHello，还是中间有人
     *  插了一手回了明文。每层都截到 80 字正好会把这半行截没。 */
    fun chain(t: Throwable?): String {
        val parts = ArrayList<String>(4)
        var cur = t
        while (cur != null) {
            val m = cur.message
            parts.add(
                cur.javaClass.simpleName +
                    if (m.isNullOrEmpty()) "" else ": " + m.replace('\n', ' ').trim()
            )
            if (parts.size >= 4) break
            cur = cur.cause
        }
        if (parts.isEmpty()) return ""
        val head = parts.dropLast(1).map { it.take(70) }
        return (head + parts.last().take(420)).joinToString(" ← ")
    }

    private fun has(hay: String, vararg needles: String): Boolean = needles.any { it in hay }
}
