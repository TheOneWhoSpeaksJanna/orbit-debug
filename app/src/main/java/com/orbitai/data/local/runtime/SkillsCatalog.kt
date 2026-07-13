package com.orbitai.data.local.runtime

import android.content.Context
import com.orbitai.core.logging.FileLogger
import org.json.JSONObject

private const val TAG = "SkillsCatalog"
private const val CATALOG_ASSET = "skills.catalog.json"

/**
 * Dynamic skills catalog — mirrors OpenClaude's command registry.
 *
 * The catalog is bundled in the APK as assets/skills.catalog.json and
 * contains all 100+ skills/commands that OpenClaude supports, organized
 * by category. Loaded at runtime to populate the Skills screen.
 */
data class SkillEntry(
    val id: String,
    val name: String,
    val description: String,
    val command: String,
    val enabled: Boolean,
    val builtIn: Boolean = false
)

data class SkillCategory(
    val name: String,
    val skills: List<SkillEntry>
)

object SkillsCatalog {

    private var cachedCategories: List<SkillCategory>? = null

    fun load(context: Context): List<SkillCategory> {
        cachedCategories?.let { return it }

        val categories = mutableListOf<SkillCategory>()
        try {
            val text = context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val array = json.optJSONArray("categories") ?: return emptyList()

            for (i in 0 until array.length()) {
                val cat = array.getJSONObject(i)
                val catName = cat.optString("name")
                val skillsArray = cat.optJSONArray("skills") ?: continue
                val skills = mutableListOf<SkillEntry>()
                for (j in 0 until skillsArray.length()) {
                    val s = skillsArray.getJSONObject(j)
                    skills.add(SkillEntry(
                        id = s.optString("id"),
                        name = s.optString("name"),
                        description = s.optString("description"),
                        command = s.optString("command"),
                        enabled = s.optBoolean("enabled", true),
                        builtIn = s.optBoolean("builtIn", false)
                    ))
                }
                categories.add(SkillCategory(catName, skills))
            }

            FileLogger.i(TAG, "Loaded ${categories.size} categories with ${categories.sumOf { it.skills.size }} skills")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to load skills catalog: ${e.message}", e)
            return fallbackCategories()
        }

        cachedCategories = categories
        return categories
    }

    fun getAllSkills(context: Context): List<SkillEntry> {
        return load(context).flatMap { it.skills }
    }

    private fun fallbackCategories(): List<SkillCategory> {
        return listOf(
            SkillCategory("Built-in", listOf(
                SkillEntry("android-environment", "Android Environment",
                    "Tells agents about their Android/PRoot environment", "android-env", true, true),
                SkillEntry("shizuku-phone-control", "Shizuku Phone Control",
                    "System-level phone control via Shizuku", "shizuku", true, true)
            ))
        )
    }
}
