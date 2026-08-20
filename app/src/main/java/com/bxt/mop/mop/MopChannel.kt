package com.bxt.mop.mop

import android.os.Handler
import android.os.Looper
import dji.v5.common.error.DJIPipeLineError
import dji.v5.common.error.IDJIError
import dji.v5.manager.mop.Pipeline
import dji.v5.manager.mop.PipelineManager
import dji.v5.utils.common.DJIExecutor
import dji.v5.utils.common.LogUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MOP 通道封装。对应 sample 的 MopVM，但修掉了其中三个会在长连接下暴露的问题：
 *
 *  1. sample 的 readData() 结尾递归调用自身且无 tailrec，长跑必然 StackOverflowError。
 *     这里改为 while 循环。
 *  2. sample 用 `String(data)` 转换整个 19004 字节缓冲，尾部残留上次数据/零字节。
 *     这里严格按 DataResult.length 截断后再交给上层。
 *  3. 超时判定改写（**注意：原先认定 sample 有缺陷，该判断已撤回**，
 *     详见 isTimeout() 的注释）。本实现改成编译安全且可自证的形式，
 *     并采样打印真实错误值。
 *
 * 另外读循环默认跑在自己的专用线程 "mop-read" 上，不占用 DJIExecutor 的 URGENT 池
 *（可用 [MopCompatMode.useDjiExecutorForRead] 切回 sample 的做法）。
 *
 * 【定位】本类只负责"把字节可靠地搬进搬出"，不理解业务协议：
 *   - 收到的是数据块不是消息，分帧由上层负责（见 Listener.onData 注释）
 *   - onData 在读线程回调，不切主线程
 * 面向持续流式传输设计；若将来要做 sample 里 MOPCmdHelper 那种一问一答的
 * 文件传输协议，在本类之上再包一层，不要往里塞。
 *
 * 【排查】上述改动均未经实证（已联调通过的是 sample 那份代码）。若行为异常，
 * 把 [compat] 设为 [MopCompatMode.SAMPLE_ORIGINAL] 即可切回 sample 原样对比，
 * 无需回退代码。逐项说明见 [MopCompatMode]。
 */
class MopChannel(initialConfig: MopConfig = MopConfig()) {

    interface Listener {
        /** 连接建立 */
        fun onConnected(config: MopConfig)

        /** 通道断开。error 为 null 表示主动断开 */
        fun onDisconnected(error: IDJIError?)

        /**
         * 收到一块数据，已按 DataResult.length 截断，是独立副本，可直接持有。
         *
         * 【线程】默认发生在读线程 "mop-read"，**不是主线程** —— 流式吞吐可能很高，
         * 逐块 post 到主线程会把 Looper 队列打爆，因此默认不做线程切换。
         * 打开 [MopCompatMode.deliverOnMainThread] 可切回 sample 的主线程回调。
         *
         * 由于线程取决于开关，实现方应当写成两种线程下都安全的形式；要更新 UI 请自行
         * post，且不要在本方法里做耗时操作 —— 阻塞在这里就等于阻塞整个读循环。
         *
         * 【边界】这是数据块不是消息。STABLE 模式类似 TCP，一次回调可能只拿到半条
         * 业务消息，也可能拿到多条。若 PSDK 侧发的是离散消息，需要在上层自行分帧。
         */
        fun onData(data: ByteArray)

        /** 非致命错误，通道仍然存活 */
        fun onError(message: String)
    }

    private val tag = "MopChannel"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "mop-io") }

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)

    /**
     * 连接参数，可在界面上修改。请在 [connect] 之前设置。
     *
     * 连接时会快照到 [activeConfig]，读循环与断开都用快照 —— 否则连接期间改了参数，
     * `disconnectPipeline` 会拿新参数去断旧通道，断不掉。
     */
    @Volatile
    var config: MopConfig = initialConfig
        set(value) {
            if (connected.get()) {
                LogUtils.w(tag, "config changed while connected, takes effect from next connect")
            }
            field = value
        }

    /** [connect] 时对 [config] 的快照，供读循环与 [closeInternal] 使用 */
    @Volatile
    private var activeConfig: MopConfig = initialConfig

    /**
     * SDK 超时错误对象的字符串形式，供 [isTimeout] 做宽松匹配。
     * 懒加载 + 兜底，避免 aar 行为异常时影响连接流程。
     */
    private val timeoutRef: String by lazy {
        runCatching { DJIPipeLineError.TIMEOUT.toString() }.getOrDefault("TIMEOUT")
    }

    /** 只打印前若干次读错误的原始文本，供收紧 [isTimeout] 用，避免刷屏 */
    private var errorSampleCount = 0

    @Volatile
    private var pipeline: Pipeline? = null

    @Volatile
    private var readThread: Thread? = null

    @Volatile
    var listener: Listener? = null

    /**
     * 兼容模式开关，见 [MopCompatMode]。默认走修复版行为。
     * 请在 [connect] 之前设置 —— 连接期间修改虽不会崩，但读循环中途换行为没有意义。
     */
    @Volatile
    var compat: MopCompatMode = MopCompatMode.FIXED
        set(value) {
            if (connected.get()) {
                LogUtils.w(tag, "compat changed while connected, takes effect from next connect")
            }
            field = value
            LogUtils.i(tag, "compat mode = $value")
        }

    val isConnected: Boolean get() = connected.get()

    /** 监听 pipeline 连接变化，可选。在 connect() 之前调用。 */
    fun observePipelines(onChanged: (Map<Int, Pipeline>) -> Unit) {
        PipelineManager.getInstance().addPipelineConnectionListener { pipelines ->
            LogUtils.i(tag, "pipelines changed: ${pipelines.keys}")
            mainHandler.post { onChanged(pipelines) }
        }
    }

    /**
     * 建立连接并启动读循环。异步执行，结果通过 [Listener] 回调。
     * 重复调用会被忽略。
     */
    fun connect() {
        if (connected.get()) {
            LogUtils.w(tag, "already connected, ignore")
            return
        }
        ioExecutor.execute {
            // 快照参数：此后读循环与断开一律用 cfg，界面上再改 config 也不影响本次连接
            val cfg = config
            activeConfig = cfg

            LogUtils.i(tag, "connecting: $cfg")
            val error = PipelineManager.getInstance().connectPipeline(
                cfg.componentIndex,
                cfg.pipelineId,
                cfg.deviceType,
                cfg.transmission,
            )
            if (error != null) {
                LogUtils.e(tag, "connectPipeline failed: $error")
                mainHandler.post { listener?.onDisconnected(error) }
                return@execute
            }

            pipeline = PipelineManager.getInstance().pipelines[cfg.pipelineId]
            if (pipeline == null) {
                LogUtils.e(tag, "connect ok but pipeline ${cfg.pipelineId} absent")
                mainHandler.post { listener?.onError("pipeline ${cfg.pipelineId} 未就绪") }
                return@execute
            }

            connected.set(true)
            mainHandler.post { listener?.onConnected(cfg) }
            startReadLoop()
        }
    }

    private fun startReadLoop() {
        running.set(true)
        if (compat.useDjiExecutorForRead) {
            // sample 原样：占用 DJIExecutor 的 URGENT 共享池线程
            DJIExecutor.getExecutorFor(DJIExecutor.Purpose.URGENT).execute { readLoop() }
        } else {
            // 专用线程路径下同步登记，避免 closeInternal 抢在 readLoop 起跑前拿到 null
            readThread = Thread({ readLoop() }, "mop-read").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun readLoop() {
        // 无论跑在专用线程还是 DJIExecutor 上，都在这里登记自己，供 closeInternal join。
        readThread = Thread.currentThread()
        // 每次新连接重新采样错误文本（只在本线程读写，无需同步）
        errorSampleCount = 0

        // 缓冲区在循环外复用，但每帧都 copy 出独立副本再上抛，
        // 避免上层拿到会被下一次读覆盖的数组。
        val buffer = ByteArray(activeConfig.readBufferSize)
        LogUtils.i(tag, "read loop started, buffer=${buffer.size}, compat=$compat")

        while (running.get()) {
            val p = pipeline ?: break
            val result = try {
                p.readData(buffer)
            } catch (t: Throwable) {
                LogUtils.e(tag, "readData threw: $t")
                if (running.get()) closeInternal(null)
                break
            }

            val len = result.length
            when {
                len > 0 -> {
                    // rawFullBuffer=true 时复刻 sample：上抛整个缓冲，尾部带残留数据。
                    // 默认按 len 截断。两种情况都 copy，绝不把复用缓冲直接交出去。
                    val frame = if (compat.rawFullBuffer) buffer.copyOf() else buffer.copyOf(len)

                    if (compat.deliverOnMainThread) {
                        // sample 原样：切主线程。流式高吞吐下会积压 Looper 队列。
                        mainHandler.post { listener?.onData(frame) }
                    } else {
                        // 直接在读线程回调。代价是 onData 必须自己保证不阻塞、
                        // 要动 UI 自己切线程。
                        listener?.onData(frame)
                    }
                }

                len == 0 -> {
                    // 正常情况下 readData 会阻塞到超时，走不到这里。
                    // 万一 aar 实现是非阻塞的，这个 1ms 让步可避免 100% 占核空转。
                    try {
                        Thread.sleep(1)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }

                else -> {
                    // disconnectOnAnyReadError=true 时复刻 sample：不区分超时，一律断开。
                    if (!compat.disconnectOnAnyReadError && isTimeout(result.error)) {
                        // 读超时是正常空闲状态，继续等下一帧
                        continue
                    }
                    LogUtils.e(tag, "read error, disconnecting: ${result.error}")
                    closeInternal(result.error)
                    break
                }
            }
        }
        LogUtils.i(tag, "read loop exited")
    }

    /**
     * 判定"读超时"。上报频率仅 0.5Hz，通道绝大多数时间空闲，因此这个判定几乎
     * 每次读操作都会走到 —— 判错的直接后果是连上就掉。
     *
     * 【关于 sample 的写法】sample 第 89 行是
     * `result.error.errorCode().equals(DJIPipeLineError.TIMEOUT)`。
     * 早先推断这是"String 与错误对象比较，恒为 false"的缺陷，但 2026-08-20 的编译
     * 错误显示 `DJIPipeLineError.TIMEOUT.errorCode()` 无法解析、而 `error.errorCode()`
     * 可以 —— 说明 TIMEOUT 本身不是错误对象，很可能就是个 String 常量。
     * 若如此，sample 那句语义是对的，此前的"缺陷"判断应予撤回。
     *
     * 【本实现】一律经 `toString()` 再比较，规避 `errorCode()` 返回类型未知带来的
     * 编译风险（`==` 作用于不相关类型会编译失败）。TIMEOUT 若真是 String，
     * `toString()` 返回自身，此式与 sample 的比较完全等价。
     *
     * 保留 `contains` 作为兜底，并采样打印原始值 —— 首次联调后据此收紧。
     */
    private fun isTimeout(error: IDJIError?): Boolean {
        if (error == null) return false

        val code = runCatching { error.errorCode().toString() }.getOrDefault("")
        val text = error.toString()

        // 采样打印 —— 这是收紧本判定所需的全部信息，首次联调时从 logcat 捞出来。
        if (errorSampleCount < ERROR_SAMPLE_LIMIT) {
            errorSampleCount++
            LogUtils.i(
                tag,
                "read error sample #$errorSampleCount: code=[$code] raw=[$text] timeoutRef=[$timeoutRef]"
            )
        }

        // 主判定：等价于 sample 的比较
        if (timeoutRef.isNotEmpty() && code == timeoutRef) return true

        // 兜底：TIMEOUT 若非 String，上面可能不命中
        return code.contains("TIMEOUT", ignoreCase = true) ||
            text.contains("TIMEOUT", ignoreCase = true)
    }

    /** 发送数据。异步执行，失败通过 [Listener.onError] 回调。 */
    fun send(data: ByteArray) {
        if (data.isEmpty()) return
        ioExecutor.execute {
            val p = pipeline
            if (p == null || !connected.get()) {
                mainHandler.post { listener?.onError("通道未连接，请先 connect") }
                return@execute
            }
            val result = try {
                p.writeData(data)
            } catch (t: Throwable) {
                mainHandler.post { listener?.onError("writeData 异常: $t") }
                return@execute
            }
            if (result.error != null) {
                LogUtils.e(tag, "writeData failed: ${result.error}")
                mainHandler.post { listener?.onError("发送失败: ${result.error}") }
            } else {
                LogUtils.i(tag, "sent ${result.length} bytes")
            }
        }
    }

    /** 主动断开。可重复调用。 */
    fun disconnect() {
        if (!connected.get() && !running.get()) return
        ioExecutor.execute { closeInternal(null) }
    }

    /**
     * 统一的关闭路径。先停循环再断 pipeline —— disconnectPipeline 会让阻塞中的
     * readData 返回，读线程随即看到 running=false 退出。
     */
    private fun closeInternal(error: IDJIError?) {
        if (!running.getAndSet(false) && !connected.get()) return

        val disconnectError = try {
            PipelineManager.getInstance().disconnectPipeline(
                // 必须用连接时的快照，不能用 config —— 否则界面改过参数后断不掉旧通道
                activeConfig.componentIndex,
                activeConfig.pipelineId,
                activeConfig.deviceType,
                activeConfig.transmission,
            )
        } catch (t: Throwable) {
            LogUtils.e(tag, "disconnectPipeline threw: $t")
            null
        }
        if (disconnectError != null) {
            LogUtils.e(tag, "disconnectPipeline failed: $disconnectError")
        }

        readThread?.let { t ->
            if (t !== Thread.currentThread()) {
                t.join(2000)
                if (t.isAlive) LogUtils.w(tag, "read thread did not exit within 2s")
            }
        }
        readThread = null
        pipeline = null
        connected.set(false)

        mainHandler.post { listener?.onDisconnected(error) }
    }

    private companion object {
        /** isTimeout 采样打印的次数上限 */
        const val ERROR_SAMPLE_LIMIT = 5
    }

    /** Activity/Service 销毁时调用，释放线程与 SDK 监听。 */
    fun release() {
        disconnect()
        PipelineManager.getInstance().clearAllPipelineConnectionListener()
        ioExecutor.shutdown()
        listener = null
    }
}
