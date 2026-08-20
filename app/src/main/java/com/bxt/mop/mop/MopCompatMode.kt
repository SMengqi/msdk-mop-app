package com.bxt.mop.mop

/**
 * 兼容模式开关：把 [MopChannel] 的行为切回 DJI sample (MopVM.kt) 的原始实现。
 *
 * 用途是排查 —— 已经和 PSDK 联调通过的是 sample 那份代码，本工程的 MopChannel
 * 做了改动但未经实证。若新工程出现异常，切到 [SAMPLE_ORIGINAL] 对比一次即可
 * 判断问题是否由这些改动引入，不必回退代码。
 *
 * 每个开关对应 MopVM.kt 中一处具体写法，行号为原文件行号。
 *
 * ---
 * 关于递归：MopVM.readData() 在第 94 行调用自身（第 70 行定义），无 tailrec，
 * 栈只增不减。这里**刻意不提供切回递归的开关** —— 它不产生任何可观察的协议行为
 * 差异，唯一后果是长跑后 StackOverflowError。复刻它对排查没有价值，只会引入风险。
 */
data class MopCompatMode(

    /**
     * `true` = sample 原样：上抛整个缓冲区，不按 `DataResult.length` 截断。
     *
     * 对应 MopVM.kt:77 `val newValueString = String(data)` —— 第 75 行取到了 len 却没用，
     * 于是 10 字节的包会带 18994 字节尾巴（首次为零字节，之后是上一包残留）。
     *
     * 注意：即便打开本开关，这里仍会 copy 一份再上抛，不会像 sample 那样把
     * 复用中的共享缓冲直接交出去 —— 那是纯粹的数据竞争，没有复刻价值。
     */
    val rawFullBuffer: Boolean = false,

    /**
     * `true` = sample 原样：任何 `length < 0` 都断开连接，不区分读超时。
     *
     * 对应 MopVM.kt:89
     * `if (!isStop && !result.error.errorCode().equals(DJIPipeLineError.TIMEOUT))`。
     * 推断该比较恒为 false（String 与错误对象比较），使条件退化成 `!isStop`。
     *
     * 该推断未经验证（aar 闭源）。若打开本开关后连接反而稳定，说明修复版的
     * 超时判定有问题，请把 `result.error.errorCode()` 的实际值打出来对齐。
     */
    val disconnectOnAnyReadError: Boolean = false,

    /**
     * `true` = sample 原样：`onData` 切回主线程回调。
     *
     * 对应 MopVM.kt:79-80 的 `receiveMessageLiveData.postValue(...)`。
     * 这一条**不是缺陷**：sample 是一发一收的手动测试页，post 到主线程完全合理。
     * 修复版改在读线程回调，是因为持续流式下逐块 post 会积压主线程队列。
     * 若你的流速率不高，打开本开关没有副作用。
     */
    val deliverOnMainThread: Boolean = false,

    /**
     * `true` = sample 原样：读循环跑在 `DJIExecutor.Purpose.URGENT` 共享线程池上。
     *
     * 对应 MopVM.kt:34。同样**不是缺陷**，只是会让一个共享池线程被永久占用。
     * 修复版改用专用线程 "mop-read"。可观察影响极小，保留开关仅为完整性。
     */
    val useDjiExecutorForRead: Boolean = false,
) {
    companion object {
        /** 默认：本工程的修复版行为 */
        val FIXED = MopCompatMode()

        /** 全量切回 sample 原样（递归除外，理由见类注释） */
        val SAMPLE_ORIGINAL = MopCompatMode(
            rawFullBuffer = true,
            disconnectOnAnyReadError = true,
            deliverOnMainThread = true,
            useDjiExecutorForRead = true,
        )
    }

    override fun toString(): String = when (this) {
        FIXED -> "FIXED(修复版)"
        SAMPLE_ORIGINAL -> "SAMPLE_ORIGINAL(sample 原样)"
        else -> "MIXED(rawBuf=$rawFullBuffer, dcOnErr=$disconnectOnAnyReadError, " +
            "mainThread=$deliverOnMainThread, djiExec=$useDjiExecutorForRead)"
    }
}
