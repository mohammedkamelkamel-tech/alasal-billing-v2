package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.BillEntity
import com.example.data.model.BillStatus
import com.example.utils.CurrencyFormatter
import com.example.data.model.PermissionKeys
import com.example.data.model.RoleType
import com.example.data.model.UserEntity
import com.example.data.model.UserProfile
import com.example.ui.components.PieChartData
import com.example.ui.components.StatPieChart
import com.example.ui.components.TrendLineChart
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.VibrantGreen
import com.example.ui.theme.WarningYellow

@Composable
fun DashboardScreen(
    currentUserProfile: UserProfile,
    canPerformAction: (String) -> Boolean,
    bills: List<BillEntity>,
    users: List<UserEntity>,
    onAddBillClick: () -> Unit,
    onBillClick: (BillEntity) -> Unit,
    onMeterReadingClick: () -> Unit
) {
    val isSupervisor = currentUserProfile.roleType == RoleType.SUPERVISOR

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Welcome Header Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(currentUserProfile.roleType.color),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSupervisor) Icons.Filled.AdminPanelSettings else Icons.Filled.Person,
                            contentDescription = currentUserProfile.roleType.titleAr,
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "أهلاً بك، ${currentUserProfile.name} 👋",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "نوع الحساب: ${currentUserProfile.roleType.titleAr}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        // Action Buttons based on Permission canAddBill
        if (canPerformAction(PermissionKeys.CAN_ADD_BILL)) {
            item {
                Text(
                    text = "الإجراءات الميدانية (متاح بناءً على الصلاحية):",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onMeterReadingClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("collector_read_meter_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) {
                        Icon(imageVector = Icons.Filled.ElectricMeter, contentDescription = "قراءة العداد")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("قراءة العداد", style = MaterialTheme.typography.labelLarge)
                    }

                    Button(
                        onClick = onAddBillClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("collector_add_bill_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VibrantGreen)
                    ) {
                        Icon(imageVector = Icons.Filled.PostAdd, contentDescription = "إضافة فاتورة")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إضافة فاتورة", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (isSupervisor) {
            item {
                AdminDashboardView(bills = bills, users = users)
            }
        } else {
            // Sub Account tailored dashboard
            if (canPerformAction(PermissionKeys.CAN_VIEW_REPORTS)) {
                item {
                    val paidCount = bills.count { it.status == "PAID" }.toFloat()
                    val unpaidOnly = bills.count { it.status == "UNPAID" }.toFloat()
                    val overdueCount = bills.count { it.status == "OVERDUE" }.toFloat()

                    StatPieChart(
                        dataList = listOf(
                            PieChartData("مدفوعة", paidCount.coerceAtLeast(1f), VibrantGreen),
                            PieChartData("غير مدفوعة", unpaidOnly.coerceAtLeast(1f), ErrorRed),
                            PieChartData("متأخرة", overdueCount.coerceAtLeast(1f), WarningYellow)
                        ),
                        centerTitle = "حالة الفواتير الحالية"
                    )
                }
            }

            item {
                Text(
                    text = "آخر الفواتير المسجلة بالنظام:",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            items(bills.take(5)) { bill ->
                BillSummaryCard(bill = bill, onClick = { onBillClick(bill) })
            }
        }
    }
}

@Composable
fun AdminDashboardView(
    bills: List<BillEntity>,
    users: List<UserEntity>
) {
    val totalUsers = users.size
    val totalBills = bills.size
    // الإيرادات = مجموع المبالغ المدفوعة فعلياً (يشمل الدفعات الجزئية، بلا ضريبة)
    val totalRevenue = bills.sumOf { it.paidAmount }
    val unpaidCount = bills.count { it.status != "PAID" }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                title = "إجمالي المستخدمين",
                value = "$totalUsers مستخدم",
                icon = Icons.Filled.People,
                color = ElectricBlue,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "إجمالي الفواتير",
                value = "$totalBills فاتورة",
                icon = Icons.Filled.ReceiptLong,
                color = VibrantGreen,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                title = "الإيرادات الشهرية",
                value = CurrencyFormatter.riyal(totalRevenue),
                icon = Icons.Filled.AttachMoney,
                color = Color(0xFFFF9800),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "فواتير غير مدفوعة",
                value = "$unpaidCount فاتورة",
                icon = Icons.Filled.Warning,
                color = ErrorRed,
                modifier = Modifier.weight(1f)
            )
        }

        val paidCount = bills.count { it.status == "PAID" }.toFloat()
        val unpaidOnly = bills.count { it.status == "UNPAID" }.toFloat()
        val overdueCount = bills.count { it.status == "OVERDUE" }.toFloat()

        StatPieChart(
            dataList = listOf(
                PieChartData("مدفوعة", paidCount.coerceAtLeast(1f), VibrantGreen),
                PieChartData("غير مدفوعة", unpaidOnly.coerceAtLeast(1f), ErrorRed),
                PieChartData("متأخرة", overdueCount.coerceAtLeast(1f), WarningYellow)
            ),
            centerTitle = "حالة الفواتير"
        )

        TrendLineChart(
            labels = listOf("السبت", "الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة"),
            values = listOf(450000f, 620000f, 510000f, 780000f, 900000f, 1100000f, 850000f)
        )
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun BillSummaryCard(
    bill: BillEntity,
    onClick: () -> Unit
) {
    val statusEnum = BillStatus.fromString(bill.status)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("bill_card_${bill.invoiceNumber}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bill.userName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "رقم الفاتورة: ${bill.invoiceNumber} | استهلاك: ${bill.consumptionKwh.toInt()} ك.و.س",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = CurrencyFormatter.riyal(bill.totalAmount),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = statusEnum.color
                )
                StatusChip(status = statusEnum)
            }
        }
    }
}

@Composable
fun StatusChip(status: BillStatus) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = status.color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = status.iconSymbol,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = status.titleAr,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = status.color
            )
        }
    }
}
