package com.bxt.mop

import android.app.Application
import android.content.Context
import com.bxt.mop.sdk.MsdkManager

/**
 * 对应 sample 的 DJIApplication + DJIAircraftApplication。
 *
 * 两处不可改动：
 *  1. attachBaseContext 里必须先调 Helper.install(this)（加壳库初始化），
 *     位置不能挪、不能删，否则 SDK 无法正常工作。
 *  2. onCreate 里第一件事就是 MsdkManager.init(this)。
 */
class MopApp : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        MsdkManager.init(this)
    }
}
