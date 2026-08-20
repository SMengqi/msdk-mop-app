package com.bxt.mop.sdk

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.network.DJINetworkManager
import dji.v5.utils.common.LogUtils

/**
 * MSDK 初始化与注册。
 *
 * 时序原样照搬 sample 的 MSDKManagerVM —— 这段是已经激活验证过的路径，不要"优化"：
 *   SDKManager.init(ctx, callback)
 *     → onInitProcess(INITIALIZE_COMPLETE)
 *       → registerApp()          <-- 必须等初始化完成才能注册，提前调必失败
 *         → onRegisterSuccess / onRegisterFailure
 *
 * 注册需要联网，因此依赖里必须有 dji-sdk-v5-networkImp（sample 工程漏了）。
 */
object MsdkManager {

    private const val TAG = "MsdkManager"

    sealed class RegisterState {
        object Idle : RegisterState()
        data class Initializing(val event: DJISDKInitEvent, val progress: Int) : RegisterState()
        object Registered : RegisterState()
        data class Failed(val error: IDJIError) : RegisterState()
    }

    private val _registerState = MutableLiveData<RegisterState>(RegisterState.Idle)
    val registerState: LiveData<RegisterState> = _registerState

    /** 飞机连接状态：first=是否连接, second=productId */
    private val _productConnection = MutableLiveData<Pair<Boolean, Int>>()
    val productConnection: LiveData<Pair<Boolean, Int>> = _productConnection

    @Volatile
    private var initialized = false

    fun init(appContext: Context) {
        if (initialized) return

        SDKManager.getInstance().init(appContext, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                LogUtils.i(TAG, "register success")
                _registerState.postValue(RegisterState.Registered)
            }

            override fun onRegisterFailure(error: IDJIError) {
                LogUtils.e(TAG, "register failure: $error")
                _registerState.postValue(RegisterState.Failed(error))
            }

            override fun onProductDisconnect(productId: Int) {
                LogUtils.i(TAG, "product disconnect: $productId")
                _productConnection.postValue(Pair(false, productId))
            }

            override fun onProductConnect(productId: Int) {
                LogUtils.i(TAG, "product connect: $productId")
                _productConnection.postValue(Pair(true, productId))
            }

            override fun onProductChanged(productId: Int) {
                LogUtils.i(TAG, "product changed: $productId")
            }

            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                LogUtils.i(TAG, "init process: $event $totalProcess")
                _registerState.postValue(RegisterState.Initializing(event, totalProcess))
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    initialized = true
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                LogUtils.i(TAG, "db download: $current/$total")
            }
        })

        // 断网时注册会失败，恢复联网后自动补一次（沿用 sample 的做法）
        DJINetworkManager.getInstance().addNetworkStatusListener { isAvailable ->
            if (initialized && isAvailable && !SDKManager.getInstance().isRegistered) {
                LogUtils.i(TAG, "network back, retry registerApp")
                SDKManager.getInstance().registerApp()
            }
        }
    }

    fun destroy() {
        SDKManager.getInstance().destroy()
        initialized = false
    }
}
