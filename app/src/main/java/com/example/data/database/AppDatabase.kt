package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.BillDao
import com.example.data.dao.PermissionDao
import com.example.data.dao.UserDao
import com.example.data.model.BillEntity
import com.example.data.model.RolePermissionEntity
import com.example.data.model.UserEntity

@Database(
    entities = [UserEntity::class, BillEntity::class, RolePermissionEntity::class],
    version = 5, // 👈 رُفع الإصدار بسبب إلغاء الضريبة وإضافة حقول الدفع الجزئي والمتأخرات
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun billDao(): BillDao
    abstract fun permissionDao(): PermissionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "electricity_billing_db"
                )
                    // 👈 ترحيل حقيقي يحافظ على بيانات الفواتير القديمة بدل حذفها
                    .addMigrations(MIGRATION_4_5)
                    // يبقى كشبكة أمان فقط للإصدارات القديمة جداً غير المُغطّاة بترحيل
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
