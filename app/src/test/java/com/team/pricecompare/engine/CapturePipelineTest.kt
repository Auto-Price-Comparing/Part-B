package com.team.pricecompare.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePipelineTest {

    @Test
    fun `包名映射到平台标识`() {
        assertEquals("meituan", CapturePipeline.platformForPackage("com.meituan.takeout"))
        assertEquals("flash", CapturePipeline.platformForPackage("me.ele"))
        assertEquals("flash", CapturePipeline.platformForPackage("com.taobao.taobao"))
        assertNull(CapturePipeline.platformForPackage("com.example.app"))
    }

    @Test
    fun `快照去重判定`() {
        // 首次入库（无历史键）不去重
        assertFalse(CapturePipeline.isDuplicateSnapshot(null, "abc"))
        // 内容键一致 → 重复
        assertTrue(CapturePipeline.isDuplicateSnapshot("abc", "abc"))
        // 内容键不同 → 新快照
        assertFalse(CapturePipeline.isDuplicateSnapshot("abc", "abd"))
    }

    @Test
    fun `对侧数据新鲜度窗口`() {
        val now = 1_000_000_000_000L
        val window = PipelineRules.COUNTERPART_FRESHNESS_MS
        // 窗口内
        assertTrue(CapturePipeline.isSnapshotFresh(now - 1000L, now))
        // 边界值视为新鲜
        assertTrue(CapturePipeline.isSnapshotFresh(now - window, now))
        assertTrue(CapturePipeline.isSnapshotFresh(now, now))
        // 超过窗口 → 过期
        assertFalse(CapturePipeline.isSnapshotFresh(now - window - 1L, now))
        // 时间戳异常（未来时间）不算新鲜
        assertFalse(CapturePipeline.isSnapshotFresh(now + 1000L, now))
    }
}
