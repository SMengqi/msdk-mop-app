package com.bxt.mop.mop

import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.mop.PipelineDeviceType
import dji.sdk.keyvalue.value.mop.TransmissionControlType

/**
 * MOP 连接参数。
 *
 * 默认值取自 PSDK 侧设计文档 `bxt-psdk-3.16.0/docs/superpowers/plans/
 * 2026-08-19-bxt-msdk-link.md` 的 Global Constraints（标注为 2026-08-19 实测），
 * **不是** DJI sample 界面上的默认值 —— 两者不同，见下表。
 *
 * | 参数            | 本工程取值    | sample 默认值 | 依据                                        |
 * |-----------------|---------------|---------------|---------------------------------------------|
 * | pipelineId      | 49200         | 49152         | msdk_link_entry.h `BXT_MSDK_LINK_CHANNEL_ID`|
 * | componentIndex  | PORT_3        | LEFT_OR_MAIN  | Global Constraints 第 18 行                 |
 * | deviceType      | PAYLOAD       | PAYLOAD       | 一致，且已实测确认                          |
 * | transmission    | STABLE        | STABLE        | 一致（PSDK 侧 TRANS_RELIABLE）              |
 * | readBufferSize  | 19004         | 19004         | 沿用 sample，与 PSDK 侧收包缓冲无关          |
 *
 * PSDK 侧 msdk_link_entry.h 明确注明："MOP 通道 ID, 需与 MSDK 侧 connectPipeline 的
 * pipelineId 一致。避开官方示例占用的 49152 / 49153。"
 *
 * 拓扑：PSDK 作 MOP server（Bind 49200 + Accept），App 连入即启用、断开即停用，
 * 无控制报文。数据方向以 PSDK → App 为主。上报频率 0.5Hz（周期 2000ms）。
 */
data class MopConfig(
    val pipelineId: Int = DEFAULT_PIPELINE_ID,
    val deviceType: PipelineDeviceType = PipelineDeviceType.PAYLOAD,
    val transmission: TransmissionControlType = TransmissionControlType.STABLE,
    val componentIndex: ComponentIndexType = ComponentIndexType.PORT_3,
    val readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE,
) {
    companion object {
        /** PSDK 侧 BXT_MSDK_LINK_CHANNEL_ID，两端必须一致 */
        const val DEFAULT_PIPELINE_ID = 49200

        /** DJI sample 的演示通道，仅在对照排查时使用 */
        const val SAMPLE_PIPELINE_ID = 49152

        const val DEFAULT_READ_BUFFER_SIZE = 19004
    }

    override fun toString(): String =
        "MopConfig(id=$pipelineId, device=$deviceType, transmission=$transmission, " +
            "component=$componentIndex, buffer=$readBufferSize)"
}
