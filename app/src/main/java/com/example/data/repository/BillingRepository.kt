package com.example.data.repository

import com.example.data.dao.BillDao
import com.example.data.dao.PermissionDao
import com.example.data.dao.UserDao
import com.example.data.model.BillEntity
import com.example.data.model.BillStatus
import com.example.data.model.RolePermissionEntity
import com.example.data.model.UserEntity
import kotlinx.coroutines.flow.Flow

class BillingRepository(
    private val userDao: UserDao,
    private val billDao: BillDao,
    private val permissionDao: PermissionDao
) {
    val allUsers: Flow<List<UserEntity>> = userDao.getAllUsers()
    val allBills: Flow<List<BillEntity>> = billDao.getAllBills()

    fun getBillsByStatus(status: String): Flow<List<BillEntity>> = billDao.getBillsByStatus(status)

    fun getBillsByUserId(userId: String): Flow<List<BillEntity>> = billDao.getBillsByUserId(userId)

    fun getBillsByAdminId(adminId: String): Flow<List<BillEntity>> = billDao.getBillsByAdminId(adminId)

    fun getUsersByAdminId(adminId: String): Flow<List<UserEntity>> = userDao.getUsersByAdminId(adminId)

    fun getPermissionsByRole(role: String): Flow<List<RolePermissionEntity>> = permissionDao.getPermissionsByRole(role)

    suspend fun getBillById(id: String): BillEntity? = billDao.getBillById(id)
    suspend fun getUserById(id: String): UserEntity? = userDao.getUserById(id)

    suspend fun getBillsForUserOnce(userId: String): List<BillEntity> = billDao.getBillsForUserOnce(userId)

    suspend fun getOutstandingBillsForUser(userId: String): List<BillEntity> =
        billDao.getOutstandingBillsForUser(userId)

    /** آخر قراءة عداد محفوظة للمشترك (تُستخدم كقراءة سابقة تلقائية). */
    suspend fun getLastReadingForUser(userId: String): Double =
        billDao.getBillsForUserOnce(userId).maxOfOrNull { it.currentReading } ?: 0.0

    /** إجمالي متأخرات المشترك = مجموع المتبقي من فواتيره غير المسددة. */
    suspend fun getArrearsForUser(userId: String): Double =
        billDao.getOutstandingBillsForUser(userId).sumOf { it.remainingAmount }

    suspend fun insertBill(bill: BillEntity): Unit = billDao.insertBill(bill)
    suspend fun updateBill(bill: BillEntity) = billDao.updateBill(bill)
    suspend fun deleteBill(bill: BillEntity) = billDao.deleteBill(bill)

    suspend fun markBillCarriedForward(id: String) = billDao.markBillCarriedForward(id)

    /**
     * تسجيل دفعة على فاتورة مع دعم الدفع الجزئي.
     * @return الفاتورة بعد التحديث، أو null إن لم تكن موجودة.
     */
    suspend fun registerPayment(
        billId: String,
        amountPaidNow: Double,
        date: String,
        method: String
    ): BillEntity? {
        val bill = billDao.getBillById(billId) ?: return null
        // السماح بالدفع الزائد: إذا دفع المشترك أكثر من المستحق، يُحفظ الفرق كرصيد
        // سالب في remainingAmount ليُخصم تلقائياً من الفاتورة القادمة.
        val newPaid = bill.paidAmount + amountPaidNow
        val newRemaining = bill.totalAmount - newPaid
        val newStatus = when {
            newRemaining <= 0.0 -> BillStatus.PAID.name
            newPaid > 0.0 -> BillStatus.PARTIAL.name
            else -> BillStatus.UNPAID.name
        }
        billDao.updateBillPayment(billId, newStatus, newPaid, newRemaining, date, method)
        return billDao.getBillById(billId)
    }

    suspend fun insertUser(user: UserEntity): Unit = userDao.insertUser(user)
    suspend fun updateUser(user: UserEntity) = userDao.updateUser(user)
    suspend fun deleteUser(user: UserEntity) = userDao.deleteUser(user)
    suspend fun updateUserStatus(id: String, isActive: Boolean) = userDao.updateUserStatus(id, isActive)

    suspend fun updatePermission(id: String, isGranted: Boolean) = permissionDao.updatePermissionStatus(id, isGranted)
}
