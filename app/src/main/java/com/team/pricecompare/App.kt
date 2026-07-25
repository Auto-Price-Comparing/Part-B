package com.team.pricecompare

import android.app.Application
import com.team.pricecompare.overlay.OverlayController

/**
 * 进程入口：注册无障碍开关监听（OverlayController），
 * 覆盖 App 在后台时用户在系统设置里切换无障碍的场景。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        OverlayController.bind(this)
    }
}
