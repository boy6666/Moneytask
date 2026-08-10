package com.moneytask.ledger.db

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全量备份/恢复（M4「导出/恢复备份」）。
 *
 * 纯本地：JSON 明文导出（MVP 简洁；二期可换 [BackupCrypto] 加密）。
 * 导出文件落在应用私有 external files 目录，通过 FileProvider 分享到文件管理/网盘；
 * 恢复用系统文件选择器读取（OpenDocument），整库清空后重灌——保证 groupId 唯一索引不冲突。
 */
object BackupManager {

    private const val MANIFEST_APP = "moneytask"

    /** 导出三表全量数据为 JSON 文件，返回生成的文件。 */
    fun export(context: Context, dao: LedgerDao): File = runBlocking(Dispatchers.IO) {
        val root = JSONObject()
            .put("app", MANIFEST_APP)
            .put("databaseVersion", 2)
            .put("exportedAt", System.currentTimeMillis())

        val accounts = JSONArray()
        dao.accounts().forEach { a ->
            accounts.put(JSONObject()
                .put("id", a.id).put("name", a.name).put("type", a.type)
                .put("bankTail", a.bankTail).put("isSystemDefault", a.isSystemDefault)
                .put("createdAt", a.createdAt).put("updatedAt", a.updatedAt))
        }
        root.put("accounts", accounts)

        val cats = JSONArray()
        dao.categoriesAll().forEach { c ->
            cats.put(JSONObject()
                .put("id", c.id).put("name", c.name).put("type", c.type)
                .put("icon", c.icon).put("isSystemDefault", c.isSystemDefault)
                .put("sortOrder", c.sortOrder).put("createdAt", c.createdAt).put("updatedAt", c.updatedAt))
        }
        root.put("categories", cats)

        val txns = JSONArray()
        dao.all().forEach { t ->
            txns.put(JSONObject()
                .put("id", t.id).put("amountFen", t.amountFen).put("type", t.type)
                .put("time", t.time).put("accountId", t.accountId).put("categoryId", t.categoryId)
                .put("merchant", t.merchant).put("paymentMethod", t.paymentMethod)
                .put("note", t.note).put("groupId", t.groupId).put("source", t.source)
                .put("isPendingReview", t.isPendingReview).put("manuallyEdited", t.manuallyEdited)
                .put("createdAt", t.createdAt).put("updatedAt", t.updatedAt))
        }
        root.put("transactions", txns)

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "moneytask_backup_$stamp.json")
        file.writeText(root.toString(2))
        file
    }

    /** 导出后生成可分享的 content:// Uri（FileProvider）。 */
    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** 从 uri 读 JSON 并整库恢复：清空三表 → 全量插入。返回恢复的账目条数。 */
    fun import(context: Context, dao: LedgerDao, uri: Uri): Int = runBlocking(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("无法读取备份文件")
        val root = JSONObject(text)
        require(root.optString("app") == MANIFEST_APP) { "不是有效的 Moneytask 备份文件" }

        // 整库重建，避免残留数据与 groupId/主键冲突。
        dao.deleteAllTransactions()
        dao.deleteAllAccounts()
        dao.deleteAllCategories()

        val accounts = root.optJSONArray("accounts")
        if (accounts != null) for (i in 0 until accounts.length()) {
            val o = accounts.getJSONObject(i)
            dao.insertAccount(AccountEntity(
                id = o.getString("id"), name = o.getString("name"), type = o.getString("type"),
                bankTail = o.optString("bankTail").ifEmpty { null },
                isSystemDefault = o.optBoolean("isSystemDefault"),
                createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
            ))
        }

        val cats = root.optJSONArray("categories")
        if (cats != null) {
            val list = ArrayList<CategoryEntity>(cats.length())
            for (i in 0 until cats.length()) {
                val o = cats.getJSONObject(i)
                list.add(CategoryEntity(
                    id = o.getString("id"), name = o.getString("name"), type = o.getString("type"),
                    icon = o.optString("icon"), isSystemDefault = o.optBoolean("isSystemDefault"),
                    sortOrder = o.optInt("sortOrder"),
                    createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
                ))
            }
            dao.insertCategories(list)
        }

        val txns = root.optJSONArray("transactions")
        if (txns != null) {
            val list = ArrayList<TransactionEntity>(txns.length())
            for (i in 0 until txns.length()) {
                val o = txns.getJSONObject(i)
                list.add(TransactionEntity(
                    id = o.getString("id"), amountFen = o.getLong("amountFen"),
                    type = o.getString("type"), time = o.getLong("time"),
                    accountId = o.optString("accountId").ifEmpty { null },
                    categoryId = o.optString("categoryId").ifEmpty { null },
                    merchant = o.optString("merchant").ifEmpty { null },
                    paymentMethod = o.optString("paymentMethod").ifEmpty { null },
                    note = o.optString("note").ifEmpty { null },
                    groupId = o.optString("groupId").ifEmpty { null },
                    source = o.getString("source"),
                    isPendingReview = o.optBoolean("isPendingReview"),
                    manuallyEdited = o.optBoolean("manuallyEdited"),
                    createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
                ))
            }
            dao.insertAll(list)
        }

        dao.count()
    }
}
