package com.bxt.mop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bxt.mop.databinding.ActivityMainBinding
import com.bxt.mop.mop.MopChannel
import com.bxt.mop.mop.MopCompatMode
import com.bxt.mop.mop.MopConfig
import com.bxt.mop.sdk.MsdkManager
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.mop.PipelineDeviceType
import dji.sdk.keyvalue.value.mop.TransmissionControlType
import dji.v5.common.error.IDJIError
import dji.v5.utils.common.LogUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 最小可用界面：等 SDK 注册成功 → 连 MOP → 收发数据。
 * 连接参数取 [MopConfig] 默认值，即 sample 中已与 PSDK 联调通过的那一组。
 */
class MainActivity : AppCompatActivity(), MopChannel.Listener {

    private val tag: String = LogUtils.getTag(this)

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: ArrayAdapter<String>

    private val logs = ArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val channel = MopChannel()
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // 流式接收统计。onData 在读线程写入，UI 定时汇总读取，故用原子类型。
    private val totalBytes = AtomicLong(0)
    private val totalChunks = AtomicInteger(0)
    private val lastChunk = AtomicReference<ByteArray?>(null)
    private var lastReportedChunks = 0

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefresh = object : Runnable {
        override fun run() {
            val chunks = totalChunks.get()
            if (chunks != lastReportedChunks) {
                lastReportedChunks = chunks
                val preview = lastChunk.get()?.let { previewOf(it) } ?: ""
                binding.tvStatus.text = getString(
                    R.string.status_streaming, chunks, totalBytes.get(), preview
                )
            }
            uiHandler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, logs)
        binding.lvLog.adapter = logAdapter

        channel.listener = this
        restoreConfigToUi(loadConfig())
        requestPermissionsIfNeeded()
        observeSdk()
        initButtons()
    }

    // ---------- 连接参数：界面 <-> MopConfig <-> SharedPreferences ----------

    /** 从 SharedPreferences 读回上次用的参数；任何一项缺失或非法都退回默认值。 */
    private fun loadConfig(): MopConfig {
        val default = MopConfig()
        return try {
            MopConfig(
                pipelineId = prefs.getInt(KEY_PIPELINE_ID, default.pipelineId),
                deviceType = PipelineDeviceType.valueOf(
                    prefs.getString(KEY_DEVICE_TYPE, null) ?: default.deviceType.name
                ),
                transmission = TransmissionControlType.valueOf(
                    prefs.getString(KEY_TRANSMISSION, null) ?: default.transmission.name
                ),
                componentIndex = ComponentIndexType.valueOf(
                    prefs.getString(KEY_COMPONENT, null) ?: default.componentIndex.name
                ),
            )
        } catch (e: IllegalArgumentException) {
            // 枚举名对不上（例如 SDK 升级后改了枚举），直接退回默认值
            LogUtils.e(tag, "stored config invalid, fall back to default: $e")
            default
        }
    }

    private fun saveConfig(config: MopConfig) {
        prefs.edit()
            .putInt(KEY_PIPELINE_ID, config.pipelineId)
            .putString(KEY_DEVICE_TYPE, config.deviceType.name)
            .putString(KEY_TRANSMISSION, config.transmission.name)
            .putString(KEY_COMPONENT, config.componentIndex.name)
            .apply()
    }

    private fun restoreConfigToUi(config: MopConfig) {
        binding.etPipelineId.setText(config.pipelineId.toString())
        binding.cbStable.isChecked = config.transmission == TransmissionControlType.STABLE
        if (config.deviceType == PipelineDeviceType.ONBOARD) {
            binding.rgDeviceType.check(R.id.rb_onboard)
        } else {
            binding.rgDeviceType.check(R.id.rb_payload)
        }

        val names = resources.getStringArray(R.array.component_index_arrays)
        val idx = names.indexOf(config.componentIndex.name)
        binding.spComponent.setSelection(if (idx >= 0) idx else 0)

        channel.config = config
        binding.tvConfig.text = config.toString()
    }

    /**
     * 按界面当前状态构造参数。通道号为空或非法时退回默认值并回填输入框，
     * 避免用一个用户看不见的值去连接。
     */
    private fun readConfigFromUi(): MopConfig {
        val default = MopConfig()

        val pipelineId = binding.etPipelineId.text.toString().trim().toIntOrNull()
            ?: default.pipelineId.also {
                appendLog("通道号为空或非法，已退回默认值 $it")
                binding.etPipelineId.setText(it.toString())
            }

        val deviceType =
            if (binding.rgDeviceType.checkedRadioButtonId == R.id.rb_onboard) {
                PipelineDeviceType.ONBOARD
            } else {
                PipelineDeviceType.PAYLOAD
            }

        val transmission =
            if (binding.cbStable.isChecked) TransmissionControlType.STABLE
            else TransmissionControlType.UNRELIABLE

        val componentIndex = try {
            ComponentIndexType.valueOf(binding.spComponent.selectedItem.toString())
        } catch (e: IllegalArgumentException) {
            LogUtils.e(tag, "unknown component index, fall back: $e")
            appendLog("组件项无法解析，已退回 ${default.componentIndex.name}")
            default.componentIndex
        }

        return MopConfig(
            pipelineId = pipelineId,
            deviceType = deviceType,
            transmission = transmission,
            componentIndex = componentIndex,
        )
    }

    /** 连接期间锁住参数控件 */
    private fun setConfigEditable(editable: Boolean) {
        binding.etPipelineId.isEnabled = editable
        binding.spComponent.isEnabled = editable
        binding.rbPayload.isEnabled = editable
        binding.rbOnboard.isEnabled = editable
        binding.cbStable.isEnabled = editable
        binding.btnResetConfig.isEnabled = editable
        binding.cbCompat.isEnabled = editable
    }

    private fun requestPermissionsIfNeeded() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSION)
        }
    }

    private fun observeSdk() {
        MsdkManager.registerState.observe(this) { state ->
            when (state) {
                is MsdkManager.RegisterState.Idle -> {
                    binding.tvStatus.setText(R.string.status_idle)
                }

                is MsdkManager.RegisterState.Initializing -> {
                    binding.tvStatus.text = getString(R.string.status_initializing, state.event.name)
                }

                is MsdkManager.RegisterState.Registered -> {
                    binding.tvStatus.setText(R.string.status_registered)
                    binding.btnConnect.isEnabled = true
                    appendLog("SDK 注册成功，可以连接 MOP")
                }

                is MsdkManager.RegisterState.Failed -> {
                    binding.tvStatus.text =
                        getString(R.string.status_register_failed, state.error.toString())
                    binding.btnConnect.isEnabled = false
                    appendLog("SDK 注册失败：${state.error}")
                }
            }
        }

        MsdkManager.productConnection.observe(this) { (isConnected, productId) ->
            appendLog("飞机 $productId ${if (isConnected) "已连接" else "已断开"}")
        }
    }

    private fun initButtons() {
        // 兼容模式只在未连接时可切 —— 读循环中途换行为没有意义。
        binding.cbCompat.setOnCheckedChangeListener { _, checked ->
            channel.compat =
                if (checked) MopCompatMode.SAMPLE_ORIGINAL else MopCompatMode.FIXED
            appendLog("兼容模式切换为：${channel.compat}")
        }

        binding.btnResetConfig.setOnClickListener {
            restoreConfigToUi(MopConfig())
            appendLog("参数已恢复默认：${channel.config}")
        }

        binding.btnConnect.setOnClickListener {
            // 每次连接都以界面当前值为准，并落盘记住
            val cfg = readConfigFromUi()
            channel.config = cfg
            saveConfig(cfg)
            binding.tvConfig.text = cfg.toString()

            // 把最终生效参数打进日志，避免"以为改了其实没生效"
            appendLog("连接中：$cfg，${channel.compat}")
            LogUtils.i(tag, "connect with $cfg, compat=${channel.compat}")
            channel.connect()
        }
        binding.btnDisconnect.setOnClickListener {
            channel.disconnect()
        }
        binding.btnSend.setOnClickListener {
            val text = binding.etPayload.text.toString()
            if (text.isEmpty()) return@setOnClickListener
            val bytes = text.toByteArray()
            channel.send(bytes)
            appendLog("→ 发送 ${bytes.size} 字节：$text")
        }
    }

    // ---------- MopChannel.Listener ----------

    override fun onConnected(config: MopConfig) {
        appendLog("MOP 已连接（${channel.compat}），开始接收流数据")
        binding.btnConnect.isEnabled = false
        binding.btnDisconnect.isEnabled = true
        binding.btnSend.isEnabled = true
        setConfigEditable(false)

        totalBytes.set(0)
        totalChunks.set(0)
        lastChunk.set(null)
        lastReportedChunks = 0
        uiHandler.removeCallbacks(uiRefresh)
        uiHandler.post(uiRefresh)
    }

    override fun onDisconnected(error: IDJIError?) {
        uiHandler.removeCallbacks(uiRefresh)
        appendLog(
            (if (error == null) "MOP 已断开" else "MOP 异常断开：$error") +
                "，累计收到 ${totalChunks.get()} 块 / ${totalBytes.get()} 字节"
        )
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.isEnabled = false
        binding.btnSend.isEnabled = false
        setConfigEditable(true)
    }

    /**
     * 【线程】默认在读线程执行；打开兼容模式后会变成主线程。
     * 这里只用原子类型累加，两种线程下都安全，切换开关无需改动本方法。
     *
     * 流式传输下调用频率可能很高，所以不逐块刷 UI —— 只累加计数，由 [uiRefresh]
     * 每 200ms 汇总刷新一次。真正的业务处理（分帧、解析、落盘）应该在这里同步
     * 做完或丢给自己的队列，但不要阻塞太久，阻塞这里就等于阻塞读循环。
     */
    override fun onData(data: ByteArray) {
        totalBytes.addAndGet(data.size.toLong())
        totalChunks.incrementAndGet()
        lastChunk.set(data)
    }

    override fun onError(message: String) {
        appendLog("错误：$message")
    }

    // ---------------------------------------

    /** 取数据块开头一小段做十六进制预览，避免流式下把整块内容拼进 UI。 */
    private fun previewOf(chunk: ByteArray): String {
        val n = minOf(chunk.size, PREVIEW_BYTES)
        val hex = StringBuilder(n * 3)
        for (i in 0 until n) {
            hex.append(String.format(Locale.US, "%02X ", chunk[i]))
        }
        if (chunk.size > n) hex.append("…")
        return hex.toString().trim()
    }

    private fun appendLog(message: String) {
        logs.add("${timeFmt.format(Date())}  $message")
        logAdapter.notifyDataSetChanged()
        binding.lvLog.setSelection(logs.size - 1)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(uiRefresh)
        channel.release()
    }

    companion object {
        private const val REQ_PERMISSION = 1001

        /** 连接参数持久化，台架调试时重启 App 不用重填 */
        private const val PREFS_NAME = "mop_config"
        private const val KEY_PIPELINE_ID = "pipeline_id"
        private const val KEY_DEVICE_TYPE = "device_type"
        private const val KEY_TRANSMISSION = "transmission"
        private const val KEY_COMPONENT = "component_index"

        /** UI 汇总刷新间隔。流式下 onData 频率远高于此，靠这个节流。 */
        private const val UI_REFRESH_MS = 200L

        /** 状态栏里预览的字节数 */
        private const val PREVIEW_BYTES = 16
    }
}
