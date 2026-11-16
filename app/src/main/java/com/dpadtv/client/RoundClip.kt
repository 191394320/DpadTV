package com.dpadtv.client

import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider

/**
 * 圆角裁剪与前景描边的兼容入口。
 *
 * clipToOutline / ViewOutlineProvider 是 API 21，View.setForeground 是 API 23，
 * 4.4 上这些方法根本不存在，调了就是 NoSuchMethodError。所以：
 *   · 新系统按原样画；
 *   · 老系统降级成「直角 + 无描边」，只丢观感不丢功能；
 *   · API 21+ 的实现放在独立内部类里，4.4 加载外层时不会去解析 ViewOutlineProvider。
 */
object RoundClip {

    fun apply(v: View, radiusPx: Float) {
        if (Build.VERSION.SDK_INT >= 21) Api21.clip(v, radiusPx)
    }

    fun foreground(v: View, drawable: Drawable) {
        if (Build.VERSION.SDK_INT >= 23) Api23.foreground(v, drawable)
    }

    private object Api21 {
        fun clip(v: View, radiusPx: Float) {
            v.clipToOutline = true
            v.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
        }
    }

    private object Api23 {
        fun foreground(v: View, drawable: Drawable) {
            v.foreground = drawable
        }
    }
}
