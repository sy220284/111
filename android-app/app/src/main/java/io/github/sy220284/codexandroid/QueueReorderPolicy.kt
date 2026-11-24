package io.github.sy220284.codexandroid

import kotlin.math.abs

/** 排序请求始终使用完整、唯一、有效的服务器队列 ID；拖动只决定目标位置。 */
internal object QueueReorderPolicy {
    fun valid(ids: List<String>): Boolean =
        ids.size > 1 && ids.none { it.isBlank() } && ids.size == ids.toSet().size

    fun move(ids: List<String>, from: Int, to: Int): List<String>? {
        if (!valid(ids) || from !in ids.indices || to !in ids.indices || from == to) return null
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun nearest(ids: List<String>, centers: Map<String, Float>, y: Float): Int? =
        ids.indices.filter { centers[ids[it]]?.isFinite() == true }
            .minByOrNull { abs(centers.getValue(ids[it]) - y) }
}
