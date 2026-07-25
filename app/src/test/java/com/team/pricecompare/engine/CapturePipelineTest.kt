package com.team.pricecompare.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapturePipelineTest {

    @Test
    fun `包名映射到平台标识`() {
        assertEquals("meituan", CapturePipeline.platformForPackage("com.meituan.takeout"))
        assertEquals("flash", CapturePipeline.platformForPackage("me.ele"))
        assertEquals("flash", CapturePipeline.platformForPackage("com.taobao.taobao"))
        assertNull(CapturePipeline.platformForPackage("com.example.app"))
    }
}
