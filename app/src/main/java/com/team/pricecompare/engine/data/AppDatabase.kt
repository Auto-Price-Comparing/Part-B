package com.team.pricecompare.engine.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** 店铺快照：payloadJson 存序列化后的 StoreInfo，M1 起由匹配/计价引擎消费。 */
@Entity(tableName = "store_snapshots")
data class StoreSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,
    val storeName: String,
    val payloadJson: String,
    val capturedAt: Long,
)

/** 红包/优惠券：M3 起由用户在主界面手工录入，供实付价估算叠加使用。 */
@Entity(tableName = "coupons")
data class CouponEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,
    val threshold: Double, // 使用门槛（满多少可用）
    val amount: Double, // 抵扣金额
    val note: String,
    val createdAt: Long,
)

/** 用户确认过的商品配对（归一化后的商品名对），确认一次永久生效。 */
@Entity(tableName = "product_matches")
data class ProductMatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nameA: String, // 归一化后的 a 侧商品名
    val nameB: String, // 归一化后的 b 侧商品名
    val createdAt: Long,
)

@Dao
interface StoreSnapshotDao {
    @Insert
    suspend fun insert(snapshot: StoreSnapshot)

    @Query("SELECT * FROM store_snapshots WHERE storeName = :name ORDER BY capturedAt DESC")
    suspend fun history(name: String): List<StoreSnapshot>

    @Query("SELECT * FROM store_snapshots ORDER BY capturedAt DESC LIMIT 50")
    suspend fun latest(): List<StoreSnapshot>

    /** 按平台+店名查时间范围内快照，升序返回，供 M3 趋势分析使用。 */
    @Query(
        "SELECT * FROM store_snapshots WHERE platform = :platform AND storeName = :name" +
            " AND capturedAt BETWEEN :from AND :to ORDER BY capturedAt ASC",
    )
    suspend fun historyInRange(platform: String, name: String, from: Long, to: Long): List<StoreSnapshot>

    /** 按店名查全部平台快照，升序返回，供 M3 跨平台对比分析使用。 */
    @Query("SELECT * FROM store_snapshots WHERE storeName = :name ORDER BY capturedAt ASC")
    suspend fun historyAllPlatforms(name: String): List<StoreSnapshot>
}

@Dao
interface CouponDao {
    @Insert
    suspend fun insert(coupon: CouponEntity)

    @Query("DELETE FROM coupons WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM coupons")
    suspend fun deleteAll()

    @Query("SELECT * FROM coupons ORDER BY createdAt DESC")
    suspend fun all(): List<CouponEntity>
}

@Dao
interface ProductMatchDao {
    @Insert
    suspend fun insert(match: ProductMatchEntity)

    /** 查重：同一归一化名对只存一次（修复 C 侧 confirm 不去重的缺陷）。 */
    @Query("SELECT COUNT(*) FROM product_matches WHERE nameA = :nameA AND nameB = :nameB")
    suspend fun count(nameA: String, nameB: String): Int

    @Query("SELECT * FROM product_matches")
    suspend fun all(): List<ProductMatchEntity>
}

/**
 * M 阶段使用 fallbackToDestructiveMigration：数据可丢，升级直接清库重建。
 * 正式版发布前必须改写为显式 Migration。
 */
@Database(
    entities = [StoreSnapshot::class, CouponEntity::class, ProductMatchEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun storeDao(): StoreSnapshotDao

    abstract fun couponDao(): CouponDao

    abstract fun productMatchDao(): ProductMatchDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "price_compare.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
