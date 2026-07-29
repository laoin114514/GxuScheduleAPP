package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import com.cherry.wakeupschedule.model.*
import com.cherry.wakeupschedule.model.dao.AccountDao
import com.cherry.wakeupschedule.model.dao.AccountSettingsDao
import com.cherry.wakeupschedule.model.dao.SemesterInfoDao
import com.google.gson.Gson
import com.gxu.jwxt.JwxtClient
import com.gxu.jwxt.model.StudentProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AccountRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val accountDao: AccountDao = db.accountDao()
    private val semesterDao: SemesterInfoDao = db.semesterInfoDao()
    private val settingsDao: AccountSettingsDao = db.accountSettingsDao()
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "active_account"
        private const val KEY_ACTIVE_ID = "active_account_id"

        @Volatile
        private var instance: AccountRepository? = null

        fun getInstance(context: Context): AccountRepository {
            return instance ?: synchronized(this) {
                instance ?: AccountRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── 活跃账号 ──

    fun getActiveAccountId(): Long = prefs.getLong(KEY_ACTIVE_ID, -1L)

    fun setActiveAccountId(id: Long) {
        prefs.edit().putLong(KEY_ACTIVE_ID, id).apply()
    }

    fun hasActiveAccount(): Boolean = getActiveAccountId() > 0

    fun clearActiveAccount() {
        prefs.edit().remove(KEY_ACTIVE_ID).apply()
    }

    // ── 账号 CRUD ──

    suspend fun getAllAccounts(): List<AccountEntity> = accountDao.getAllAccounts()

    fun getAllAccountsFlow(): Flow<List<AccountEntity>> = accountDao.getAllAccountsFlow()

    suspend fun getAccountById(id: Long): AccountEntity? = accountDao.getAccountById(id)

    suspend fun getAccountByUsername(username: String): AccountEntity? =
        accountDao.getAccountByUsername(username)

    suspend fun getActiveAccount(): AccountEntity? {
        val id = getActiveAccountId()
        if (id <= 0) return null
        return accountDao.getAccountById(id)
    }

    suspend fun bindAccount(username: String, password: String): AccountEntity {
        val existing = accountDao.getAccountByUsername(username)
        if (existing != null) {
            val updated = existing.copy(
                password = password,
                lastActive = System.currentTimeMillis()
            )
            accountDao.insertAccount(updated)
            setActiveAccountId(updated.id)
            return updated
        }
        val account = AccountEntity(
            username = username,
            password = password,
            boundAt = System.currentTimeMillis(),
            lastActive = System.currentTimeMillis()
        )
        val id = accountDao.insertAccount(account)
        val created = account.copy(id = id)
        setActiveAccountId(id)
        return created
    }

    suspend fun switchAccount(accountId: Long) {
        accountDao.updateLastActive(accountId, System.currentTimeMillis())
        setActiveAccountId(accountId)
    }

    suspend fun unbindAccount(accountId: Long) {
        val account = accountDao.getAccountById(accountId) ?: return

        try {
            withContext(Dispatchers.IO) {
                val client = JwxtClient(account.username, account.password)
                client.logout()
            }
        } catch (_: Exception) {
            // logout 失败不阻塞解绑流程
        }

        accountDao.deleteAccount(account)

        if (getActiveAccountId() == accountId) {
            clearActiveAccount()
            val remaining = accountDao.getAllAccounts()
            if (remaining.isNotEmpty()) {
                setActiveAccountId(remaining.first().id)
            }
        }
    }

    // ── 个人信息 ──

    suspend fun saveProfile(accountId: Long, profile: StudentProfile) {
        val gradeYear = profile.getGrade()?.toIntOrNull() ?: 0
        val studyYears = profile.getSchoolingLength()?.toIntOrNull() ?: 4
        val entity = AccountProfileEntity(
            accountId = accountId,
            profileJson = gson.toJson(profile),
            gradeYear = gradeYear,
            studyYears = studyYears,
            savedAt = System.currentTimeMillis()
        )
        accountDao.insertProfile(entity)
    }

    suspend fun getProfile(accountId: Long): StudentProfile? {
        val entity = accountDao.getProfile(accountId) ?: return null
        return try {
            gson.fromJson(entity.profileJson, StudentProfile::class.java)
        } catch (_: Exception) { null }
    }

    suspend fun getGradeYear(accountId: Long): Int {
        return accountDao.getProfile(accountId)?.gradeYear ?: 0
    }

    suspend fun getStudyYears(accountId: Long): Int {
        return accountDao.getProfile(accountId)?.studyYears ?: 4
    }

    // ── 学期信息 ──

    suspend fun saveSemesters(semesters: List<SemesterInfoEntity>) {
        semesterDao.insertSemesters(semesters)
    }

    suspend fun updateSemester(semester: SemesterInfoEntity) {
        semesterDao.updateSemester(semester)
    }

    suspend fun getSemesters(accountId: Long): List<SemesterInfoEntity> {
        return semesterDao.getSemesters(accountId)
    }

    fun getSemestersFlow(accountId: Long): Flow<List<SemesterInfoEntity>> {
        return semesterDao.getSemestersFlow(accountId)
    }

    suspend fun getSemester(accountId: Long, year: String, termCode: String): SemesterInfoEntity? {
        return semesterDao.getSemester(accountId, year, termCode)
    }

    /**
     * 根据 grade + schoolingLength 推算学期列表
     */
    fun generateSemesterList(gradeYear: Int, studyYears: Int): List<SemesterInfoEntity> {
        val labels = listOf("大一", "大二", "大三", "大四")
        val semesters = mutableListOf<SemesterInfoEntity>()
        for (i in 0 until studyYears.coerceAtMost(4)) {
            val year = gradeYear + i
            val yearStr = "${year}-${year + 1}学年"
            semesters.add(SemesterInfoEntity(
                accountId = 0,
                academicYear = yearStr,
                termCode = "3",
                gradeLabel = labels[i]
            ))
            semesters.add(SemesterInfoEntity(
                accountId = 0,
                academicYear = yearStr,
                termCode = "12",
                gradeLabel = labels[i]
            ))
        }
        return semesters
    }

    // ── 账号设置 ──

    suspend fun getAccountSettings(accountId: Long): AccountSettings {
        val entity = settingsDao.getSettings(accountId) ?: return AccountSettings()
        return try {
            gson.fromJson(entity.settingsJson, AccountSettings::class.java)
        } catch (_: Exception) { AccountSettings() }
    }

    suspend fun saveAccountSettings(accountId: Long, settings: AccountSettings) {
        val entity = AccountSettingsEntity(
            accountId = accountId,
            settingsJson = gson.toJson(settings)
        )
        settingsDao.insertSettings(entity)
    }
}
