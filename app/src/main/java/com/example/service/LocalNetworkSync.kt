package com.example.service

import android.content.Context
import android.util.Log
import com.example.data.database.AppDatabase
import com.example.data.model.AccessKey
import com.example.data.model.BillEntity
import com.example.data.model.UserEntity
import com.example.data.model.MeterReadingEntity
import com.example.data.repository.LocalAccessKeyRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.Types
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

/**
 * مزامنة محلية داخل شبكة Wi‑Fi.
 *
 * جهاز ADMIN يعمل كخادم داخل الشبكة، والأجهزة الأخرى تكتشفه عبر UDP
 * ثم تستخدم TCP لإرسال التغييرات وطلب نسخة حديثة من البيانات.
 *
 * لا توجد خدمة سحابية؛ الاتصال بين الأجهزة محلي فقط.
 */
class LocalNetworkSync(
    private val context: Context,
    private val db: AppDatabase,
    private val accessKeys: LocalAccessKeyRepository
) {
    companion object {
        private const val TAG = "LocalNetworkSync"
        private const val TCP_PORT = 47821
        private const val UDP_PORT = 47822
        private const val DISCOVERY_PREFIX = "ELECTRICITY_BILLING_ADMIN:"
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val billListAdapter = moshi.adapter<List<BillEntity>>(
        Types.newParameterizedType(List::class.java, BillEntity::class.java)
    )
    private val readingListAdapter = moshi.adapter<List<MeterReadingEntity>>(
        Types.newParameterizedType(List::class.java, MeterReadingEntity::class.java)
    )
    private val userListAdapter = moshi.adapter<List<UserEntity>>(
        Types.newParameterizedType(List::class.java, UserEntity::class.java)
    )
    private val keyListAdapter = moshi.adapter<List<AccessKey>>(
        Types.newParameterizedType(List::class.java, AccessKey::class.java)
    )

    @Volatile private var adminMode = false
    @Volatile private var adminHost: String? = null
    private var server: ServerSocket? = null

    fun startAsAdmin() {
        if (adminMode) return
        adminMode = true
        scope.launch { runServer() }
        scope.launch { broadcastAdminPresence() }
    }

    fun startAsClient() {
        adminMode = false
        scope.launch { clientDiscoveryLoop() }
    }

    fun stop() {
        adminMode = false
        server?.close()
        server = null
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    suspend fun saveBill(bill: BillEntity): Boolean {
        if (adminMode) return true
        return sendOperation(
            JSONObjectPayload.upsertBill(billListAdapter.toJson(listOf(bill)))
        )
    }

    suspend fun deleteBill(bill: BillEntity): Boolean {
        if (adminMode) return true
        return sendOperation(JSONObjectPayload.delete("bill", bill.id))
    }

    suspend fun saveMeterReading(reading: MeterReadingEntity): Boolean {
        if (adminMode) return true
        return sendOperation(JSONObjectPayload.upsertReading(readingListAdapter.toJson(listOf(reading))))
    }

    suspend fun saveUser(user: UserEntity): Boolean {
        if (adminMode) return true
        return sendOperation(
            JSONObjectPayload.upsertUser(userListAdapter.toJson(listOf(user)))
        )
    }

    suspend fun deleteUser(user: UserEntity): Boolean {
        if (adminMode) return true
        return sendOperation(JSONObjectPayload.delete("user", user.id))
    }

    private suspend fun runServer() {
        try {
            server = ServerSocket(TCP_PORT)
            while (scope.isActive) {
                val socket = server!!.accept()
                scope.launch {
                    socket.use {
                        val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                        val writer = PrintWriter(it.getOutputStream(), true)
                        val request = reader.readLine() ?: return@launch
                        val response = handleRequest(request)
                        writer.println(response)
                    }
                }
            }
        } catch (e: Exception) {
            if (scope.isActive) Log.e(TAG, "Server stopped: ${e.message}")
        }
    }

    private suspend fun handleRequest(request: String): String {
        return try {
            val o = org.json.JSONObject(request)
            when (o.optString("type")) {
                "GET_SNAPSHOT" -> {
                    syncSnapshotFromJson(
                        users = emptyList(),
                        bills = emptyList(),
                        keys = emptyList(),
                        readings = emptyList()
                    )
                    snapshotJson()
                }
                "UPSERT_BILL" -> {
                    val list = billListAdapter.fromJson(o.optString("data")).orEmpty()
                    list.forEach { db.billDao().insertBill(it) }
                    snapshotJson()
                }
                "UPSERT_READING" -> {
                    val list = readingListAdapter.fromJson(o.optString("data")).orEmpty()
                    list.forEach { db.meterReadingDao().insert(it) }
                    snapshotJson()
                }
                "UPSERT_USER" -> {
                    val list = userListAdapter.fromJson(o.optString("data")).orEmpty()
                    list.forEach { db.userDao().insertUser(it) }
                    snapshotJson()
                }
                "DELETE" -> {
                    when (o.optString("entity")) {
                        "bill" -> db.billDao().getBillById(o.optString("id"))?.let { db.billDao().deleteBill(it) }
                        "user" -> db.userDao().getUserById(o.optString("id"))?.let { db.userDao().deleteUser(it) }
                    }
                    snapshotJson()
                }
                else -> org.json.JSONObject().put("ok", false).toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request error: ${e.message}")
            org.json.JSONObject().put("ok", false).put("error", e.message ?: "sync error").toString()
        }
    }

    private suspend fun syncSnapshotFromJson(
        users: List<UserEntity>,
        bills: List<BillEntity>,
        keys: List<AccessKey>,
        readings: List<MeterReadingEntity>
    ) {
        // Reserved for future authenticated full snapshot import.
    }

    private suspend fun snapshotJson(): String {
        val users = db.userDao().getAllUsers().first()
        val bills = db.billDao().getAllBills().first()
        val keys = accessKeys.getAllLocalAccessKeys()
        val readings = db.meterReadingDao().getAll().first()
        return org.json.JSONObject().apply {
            put("ok", true)
            put("users", org.json.JSONArray(userListAdapter.toJson(users)))
            put("bills", org.json.JSONArray(billListAdapter.toJson(bills)))
            put("keys", org.json.JSONArray(keyListAdapter.toJson(keys)))
            put("readings", org.json.JSONArray(readingListAdapter.toJson(readings)))
        }.toString()
    }

    private suspend fun clientDiscoveryLoop() {
        while (scope.isActive) {
            try {
                val host = discoverAdmin()
                if (host != null) {
                    adminHost = host
                    requestSnapshot(host)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Discovery/sync: ${e.message}")
            }
            delay(15_000)
        }
    }

    private suspend fun discoverAdmin(): String? = withContext(Dispatchers.IO) {
        DatagramSocket(UDP_PORT).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 1500
            val data = ByteArray(512)
            val packet = DatagramPacket(data, data.size)
            try {
                socket.receive(packet)
                val msg = String(packet.data, 0, packet.length)
                if (msg.startsWith(DISCOVERY_PREFIX)) packet.address.hostAddress else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun broadcastAdminPresence() {
        while (scope.isActive && adminMode) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val bytes = "$DISCOVERY_PREFIX$TCP_PORT".toByteArray()
                    val packet = DatagramPacket(
                        bytes, bytes.size,
                        InetAddress.getByName("255.255.255.255"), UDP_PORT
                    )
                    socket.send(packet)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Broadcast: ${e.message}")
            }
            delay(3000)
        }
    }

    private suspend fun sendOperation(payload: String): Boolean {
        val host = adminHost ?: discoverAdmin().also { adminHost = it } ?: return false
        return try {
            val response = request(host, payload)
            applySnapshot(response)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Operation failed: ${e.message}")
            false
        }
    }

    private suspend fun request(host: String, payload: String): String =
        withContext(Dispatchers.IO) {
            java.net.Socket(host, TCP_PORT).use { socket ->
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.println(payload)
                reader.readLine() ?: ""
            }
        }

    private suspend fun requestSnapshot(host: String) {
        try {
            val response = request(host, org.json.JSONObject().put("type", "GET_SNAPSHOT").toString())
            applySnapshot(response)
        } catch (e: Exception) {
            Log.d(TAG, "Snapshot failed: ${e.message}")
        }
    }

    private suspend fun applySnapshot(response: String) {
        val o = org.json.JSONObject(response)
        if (!o.optBoolean("ok")) return

        val users = userListAdapter.fromJson(o.optJSONArray("users")?.toString() ?: "[]").orEmpty()
        val bills = billListAdapter.fromJson(o.optJSONArray("bills")?.toString() ?: "[]").orEmpty()
        val keys = keyListAdapter.fromJson(o.optJSONArray("keys")?.toString() ?: "[]").orEmpty()
        val readings = readingListAdapter.fromJson(o.optJSONArray("readings")?.toString() ?: "[]").orEmpty()

        db.userDao().deleteAllUsers()
        db.billDao().deleteAllBills()
        db.meterReadingDao().deleteAll()
        users.takeIf { it.isNotEmpty() }?.let { db.userDao().insertUsers(it) }
        bills.takeIf { it.isNotEmpty() }?.let { db.billDao().insertBills(it) }
        readings.takeIf { it.isNotEmpty() }?.let { db.meterReadingDao().insertAll(it) }
        val currentKeys = accessKeys.getAllLocalAccessKeys()
        currentKeys.filter { local -> keys.none { it.id == local.id } }
            .forEach { accessKeys.deleteAccessKey(it.id) }
        keys.forEach { accessKeys.saveAccessKey(it) }
    }

    private object JSONObjectPayload {
        fun upsertBill(data: String) = org.json.JSONObject()
            .put("type", "UPSERT_BILL").put("data", data).toString()

        fun upsertReading(data: String) = org.json.JSONObject()
            .put("type", "UPSERT_READING").put("data", data).toString()

        fun upsertUser(data: String) = org.json.JSONObject()
            .put("type", "UPSERT_USER").put("data", data).toString()

        fun delete(entity: String, id: String) = org.json.JSONObject()
            .put("type", "DELETE").put("entity", entity).put("id", id).toString()
    }
}
