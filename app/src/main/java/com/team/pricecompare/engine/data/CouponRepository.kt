package com.team.pricecompare.engine.data

import android.content.Context

/**
 * 红包/优惠券仓库：M3 主界面手工录入入口。
 * 全部 suspend，失败一律优雅降级（落库异常由 Room 抛出前已在上层 runCatching 兜底）。
 */
class CouponRepository(context: Context) {

    private val dao = AppDatabase.get(context.applicationContext).couponDao()

    /** 录入一条红包，createdAt 由仓库统一打时间戳。 */
    suspend fun save(platform: String, threshold: Double, amount: Double, note: String = "") {
        dao.insert(
            CouponEntity(
                platform = platform,
                threshold = threshold,
                amount = amount,
                note = note,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 全部红包，按录入时间倒序。 */
    suspend fun list(): List<CouponEntity> = dao.all()

    /** 按 id 删除单条（录入错误时撤销用）。 */
    suspend fun delete(id: Long) = dao.delete(id)

    /** 清空全部红包。 */
    suspend fun deleteAll() = dao.deleteAll()
}
