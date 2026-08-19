package com.example.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * ترحيل قاعدة البيانات من الإصدار 4 إلى 5.
 *
 * سبب الترحيل:
 * - حذف العمود taxAmount نهائياً (إلغاء الضريبة).
 * - إضافة الأعمدة: previousDebt, paidAmount, remainingAmount, issueDate.
 *
 * بما أن SQLite في إصدارات أندرويد القديمة لا يدعم DROP COLUMN، يتم إنشاء جدول
 * جديد ونقل البيانات إليه ثم استبدال الجدول القديم — مع الحفاظ على كل البيانات.
 *
 * القيم المحسوبة أثناء النقل:
 * - totalAmount الجديد = subtotalAmount (بدون ضريبة).
 * - paidAmount = totalAmount الجديد للفواتير المدفوعة، و0 لغيرها.
 * - remainingAmount = المتبقي وفق ذلك.
 * - issueDate = readingDate إن وُجد.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bills_new (
                id TEXT NOT NULL PRIMARY KEY,
                adminId TEXT NOT NULL,
                invoiceNumber TEXT NOT NULL,
                userId TEXT NOT NULL,
                userName TEXT NOT NULL,
                userPhone TEXT NOT NULL,
                userAddress TEXT NOT NULL,
                prevReading REAL NOT NULL,
                currentReading REAL NOT NULL,
                consumptionKwh REAL NOT NULL,
                unitPrice REAL NOT NULL,
                subtotalAmount REAL NOT NULL,
                previousDebt REAL NOT NULL DEFAULT 0,
                totalAmount REAL NOT NULL,
                paidAmount REAL NOT NULL DEFAULT 0,
                remainingAmount REAL NOT NULL DEFAULT 0,
                issueDate TEXT NOT NULL DEFAULT '',
                dueDate TEXT NOT NULL,
                status TEXT NOT NULL,
                readingDate TEXT NOT NULL,
                notes TEXT NOT NULL,
                paymentDate TEXT NOT NULL,
                paymentMethod TEXT NOT NULL,
                readingImageUri TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO bills_new (
                id, adminId, invoiceNumber, userId, userName, userPhone, userAddress,
                prevReading, currentReading, consumptionKwh, unitPrice, subtotalAmount,
                previousDebt, totalAmount, paidAmount, remainingAmount, issueDate,
                dueDate, status, readingDate, notes, paymentDate, paymentMethod, readingImageUri
            )
            SELECT
                id, adminId, invoiceNumber, userId, userName, userPhone, userAddress,
                prevReading, currentReading, consumptionKwh, unitPrice, subtotalAmount,
                0,
                subtotalAmount,
                CASE WHEN status = 'PAID' THEN subtotalAmount ELSE 0 END,
                CASE WHEN status = 'PAID' THEN 0 ELSE subtotalAmount END,
                readingDate,
                dueDate, status, readingDate, notes, paymentDate, paymentMethod, readingImageUri
            FROM bills
            """.trimIndent()
        )

        db.execSQL("DROP TABLE bills")
        db.execSQL("ALTER TABLE bills_new RENAME TO bills")
    }
}
