package com.ufo.galaxy.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * UFO Galaxy - 自主学习引擎
 * 
 * 功能：
 * 1. 操作模式学习
 * 2. 用户偏好学习
 * 3. 应用适配学习
 * 4. 技能记忆 (MemOS 风格)
 * 
 * 版本：1.0.0
 * 日期：2026-02-02
 */
class AutonomousLearning(private val context: Context) {
    
    companion object {
        private const val TAG = "AutonomousLearning"
        private const val LEARNING_DB_NAME = "learning_data.json"
        private const val MAX_HISTORY_SIZE = 1000
    }
    
    // 学习数据存储
    private val learningDataFile: File by lazy {
        File(context.filesDir, LEARNING_DB_NAME)
    }
    
    // 操作历史
    private val operationHistory = mutableListOf<OperationRecord>()
    
    // 学习到的模式
    private val learnedPatterns = mutableMapOf<String, ActionPattern>()
    
    // 用户偏好
    private val userPreferences = mutableMapOf<String, Any>()
    
    // 应用适配规则
    private val appAdaptations = mutableMapOf<String, AppAdaptation>()
    
    init {
        loadLearningData()
    }
    
    // ============================================================================
    // 操作记录
    // ============================================================================
    
    /**
     * 记录操作
     */
    fun recordOperation(
        packageName: String,
        instruction: String,
        action: GUIAction,
        success: Boolean,
        duration: Long
    ) {
        val record = OperationRecord(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            instruction = instruction,
            action = action,
            success = success,
            duration = duration
        )
        
        operationHistory.add(record)
        
        // 限制历史大小
        if (operationHistory.size > MAX_HISTORY_SIZE) {
            operationHistory.removeAt(0)
        }
        
        // 触发学习
        if (operationHistory.size % 10 == 0) {
            analyzePatterns()
        }
        
        Log.d(TAG, "Recorded operation: $instruction -> ${action.action}, success=$success")
    }
    
    // ============================================================================
    // 模式学习
    // ============================================================================
    
    /**
     * 分析操作模式
     */
    private fun analyzePatterns() {
        // 按应用分组
        val groupedByApp = operationHistory.groupBy { it.packageName }
        
        for ((packageName, records) in groupedByApp) {
            // 分析成功的操作
            val successRecords = records.filter { it.success }
            
            // 提取常见指令模式
            val instructionPatterns = successRecords
                .groupBy { normalizeInstruction(it.instruction) }
                .filter { it.value.size >= 3 }  // 至少出现 3 次
            
            for ((pattern, patternRecords) in instructionPatterns) {
                // 找出最常用的动作
                val commonAction = patternRecords
                    .groupBy { it.action.action }
                    .maxByOrNull { it.value.size }
                    ?.key
                
                if (commonAction != null) {
                    val avgX = patternRecords.map { it.action.x }.average().toInt()
                    val avgY = patternRecords.map { it.action.y }.average().toInt()
                    
                    learnedPatterns["$packageName:$pattern"] = ActionPattern(
                        packageName = packageName,
                        instructionPattern = pattern,
                        preferredAction = commonAction,
                        avgX = avgX,
                        avgY = avgY,
                        confidence = patternRecords.size.toFloat() / records.size
                    )
                }
            }
        }
        
        Log.d(TAG, "Analyzed patterns: ${learnedPatterns.size} patterns learned")
    }
    
    /**
     * 标准化指令 (提取关键词)
     */
    private fun normalizeInstruction(instruction: String): String {
        return instruction
            .replace(Regex("[0-9]+"), "#NUM#")
            .replace(Regex("\"[^\"]+\""), "#STR#")
            .lowercase()
            .trim()
    }
    
    /**
     * 根据学习到的模式推荐动作
     */
    fun recommendAction(packageName: String, instruction: String): ActionPattern? {
        val normalizedInstruction = normalizeInstruction(instruction)
        val key = "$packageName:$normalizedInstruction"
        
        return learnedPatterns[key] ?: learnedPatterns.values
            .filter { it.packageName == packageName }
            .maxByOrNull { similarity(normalizedInstruction, it.instructionPattern) }
            ?.takeIf { similarity(normalizedInstruction, it.instructionPattern) > 0.7 }
    }
    
    /**
     * 计算字符串相似度
     */
    private fun similarity(s1: String, s2: String): Float {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        
        if (longer.isEmpty()) return 1.0f
        
        val editDistance = levenshteinDistance(longer, shorter)
        return (longer.length - editDistance).toFloat() / longer.length
    }
    
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        
        return dp[s1.length][s2.length]
    }
    
    // ============================================================================
    // 用户偏好学习
    // ============================================================================
    
    /**
     * 学习用户偏好
     */
    fun learnPreference(key: String, value: Any) {
        userPreferences[key] = value
        Log.d(TAG, "Learned preference: $key = $value")
    }
    
    /**
     * 获取用户偏好
     */
    fun getPreference(key: String, default: Any? = null): Any? {
        return userPreferences[key] ?: default
    }
    
    // ============================================================================
    // 应用适配学习
    // ============================================================================
    
    /**
     * 学习应用适配规则
     */
    fun learnAppAdaptation(packageName: String, rule: String, value: Any) {
        val adaptation = appAdaptations.getOrPut(packageName) {
            AppAdaptation(packageName)
        }
        adaptation.rules[rule] = value
        Log.d(TAG, "Learned app adaptation: $packageName.$rule = $value")
    }
    
    /**
     * 获取应用适配规则
     */
    fun getAppAdaptation(packageName: String): AppAdaptation? {
        return appAdaptations[packageName]
    }
    
    // ============================================================================
    // 技能记忆 (MemOS 风格)
    // ============================================================================
    
    /**
     * 保存技能
     */
    fun saveSkill(name: String, steps: List<SkillStep>) {
        val skillFile = File(context.filesDir, "skills/$name.json")
        skillFile.parentFile?.mkdirs()
        
        val json = JSONObject().apply {
            put("name", name)
            put("created", System.currentTimeMillis())
            put("steps", JSONArray().apply {
                steps.forEach { step ->
                    put(JSONObject().apply {
                        put("action", step.action)
                        put("params", step.params)
                        put("waitTime", step.waitTime)
                    })
                }
            })
        }
        
        skillFile.writeText(json.toString())
        Log.d(TAG, "Saved skill: $name with ${steps.size} steps")
    }
    
    /**
     * 加载技能
     */
    fun loadSkill(name: String): List<SkillStep>? {
        val skillFile = File(context.filesDir, "skills/$name.json")
        if (!skillFile.exists()) return null
        
        return try {
            val json = JSONObject(skillFile.readText())
            val stepsArray = json.getJSONArray("steps")
            
            (0 until stepsArray.length()).map { i ->
                val stepJson = stepsArray.getJSONObject(i)
                SkillStep(
                    action = stepJson.getString("action"),
                    params = stepJson.getJSONObject("params"),
                    waitTime = stepJson.optLong("waitTime", 0)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load skill: ${e.message}")
            null
        }
    }
    
    /**
     * 列出所有技能
     */
    fun listSkills(): List<String> {
        val skillsDir = File(context.filesDir, "skills")
        return skillsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
    
    // ============================================================================
    // 数据持久化
    // ============================================================================
    
    /**
     * 保存学习数据
     */
    suspend fun saveLearningData() = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("patterns", JSONArray().apply {
                    learnedPatterns.values.forEach { pattern ->
                        put(pattern.toJson())
                    }
                })
                put("preferences", JSONObject(userPreferences))
                put("adaptations", JSONArray().apply {
                    appAdaptations.values.forEach { adaptation ->
                        put(adaptation.toJson())
                    }
                })
                put("lastSaved", System.currentTimeMillis())
            }
            
            learningDataFile.writeText(json.toString())
            Log.d(TAG, "Saved learning data")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save learning data: ${e.message}")
        }
    }
    
    /**
     * 加载学习数据
     */
    private fun loadLearningData() {
        try {
            if (!learningDataFile.exists()) return
            
            val json = JSONObject(learningDataFile.readText())
            
            // 加载模式
            json.optJSONArray("patterns")?.let { patterns ->
                for (i in 0 until patterns.length()) {
                    val patternJson = patterns.getJSONObject(i)
                    val pattern = ActionPattern.fromJson(patternJson)
                    learnedPatterns["${pattern.packageName}:${pattern.instructionPattern}"] = pattern
                }
            }
            
            // 加载偏好
            json.optJSONObject("preferences")?.let { prefs ->
                prefs.keys().forEach { key ->
                    userPreferences[key] = prefs.get(key)
                }
            }
            
            // 加载适配规则
            json.optJSONArray("adaptations")?.let { adaptations ->
                for (i in 0 until adaptations.length()) {
                    val adaptationJson = adaptations.getJSONObject(i)
                    val adaptation = AppAdaptation.fromJson(adaptationJson)
                    appAdaptations[adaptation.packageName] = adaptation
                }
            }
            
            Log.d(TAG, "Loaded learning data: ${learnedPatterns.size} patterns, ${userPreferences.size} preferences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load learning data: ${e.message}")
        }
    }
    
    // ============================================================================
    // 统计信息
    // ============================================================================
    
    /**
     * 获取学习统计
     */
    fun getStats(): LearningStats {
        return LearningStats(
            totalOperations = operationHistory.size,
            successRate = if (operationHistory.isNotEmpty()) {
                operationHistory.count { it.success }.toFloat() / operationHistory.size
            } else 0f,
            learnedPatterns = learnedPatterns.size,
            userPreferences = userPreferences.size,
            appAdaptations = appAdaptations.size,
            skills = listSkills().size
        )
    }
}

// ============================================================================
// 数据类
// ============================================================================

/**
 * 操作记录
 */
data class OperationRecord(
    val timestamp: Long,
    val packageName: String,
    val instruction: String,
    val action: GUIAction,
    val success: Boolean,
    val duration: Long
)

/**
 * 动作模式
 */
data class ActionPattern(
    val packageName: String,
    val instructionPattern: String,
    val preferredAction: String,
    val avgX: Int,
    val avgY: Int,
    val confidence: Float
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("packageName", packageName)
            put("instructionPattern", instructionPattern)
            put("preferredAction", preferredAction)
            put("avgX", avgX)
            put("avgY", avgY)
            put("confidence", confidence)
        }
    }
    
    companion object {
        fun fromJson(json: JSONObject): ActionPattern {
            return ActionPattern(
                packageName = json.getString("packageName"),
                instructionPattern = json.getString("instructionPattern"),
                preferredAction = json.getString("preferredAction"),
                avgX = json.getInt("avgX"),
                avgY = json.getInt("avgY"),
                confidence = json.getDouble("confidence").toFloat()
            )
        }
    }
}

/**
 * 应用适配规则
 */
data class AppAdaptation(
    val packageName: String,
    val rules: MutableMap<String, Any> = mutableMapOf()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("packageName", packageName)
            put("rules", JSONObject(rules))
        }
    }
    
    companion object {
        fun fromJson(json: JSONObject): AppAdaptation {
            val rules = mutableMapOf<String, Any>()
            json.optJSONObject("rules")?.let { rulesJson ->
                rulesJson.keys().forEach { key ->
                    rules[key] = rulesJson.get(key)
                }
            }
            return AppAdaptation(
                packageName = json.getString("packageName"),
                rules = rules
            )
        }
    }
}

/**
 * 技能步骤
 */
data class SkillStep(
    val action: String,
    val params: JSONObject,
    val waitTime: Long = 0
)

/**
 * 学习统计
 */
data class LearningStats(
    val totalOperations: Int,
    val successRate: Float,
    val learnedPatterns: Int,
    val userPreferences: Int,
    val appAdaptations: Int,
    val skills: Int
)
