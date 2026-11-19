package com.google.mediapipe.examples.poselandmarker

import android.content.Context

data class UserProfile(
    val edad: Int,
    val alturaCm: Int,
    val pesoKg: Float,
    val genero: String
)

class UserProfileRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasProfile(): Boolean = prefs.contains(KEY_EDAD) && prefs.contains(KEY_ALTURA) &&
        prefs.contains(KEY_PESO) && prefs.contains(KEY_GENERO)

    fun saveProfile(profile: UserProfile) {
        prefs.edit().putInt(KEY_EDAD, profile.edad).putInt(KEY_ALTURA, profile.alturaCm)
            .putFloat(KEY_PESO, profile.pesoKg).putString(KEY_GENERO, profile.genero).apply()
    }

    fun getProfile(): UserProfile? {
        if (!hasProfile()) return null
        return UserProfile(prefs.getInt(KEY_EDAD, 0), prefs.getInt(KEY_ALTURA, 0),
            prefs.getFloat(KEY_PESO, 0f), prefs.getString(KEY_GENERO, "Otro") ?: "Otro")
    }

    private companion object {
        const val PREFS_NAME = "user_profile_prefs"
        const val KEY_EDAD = "edad"
        const val KEY_ALTURA = "altura_cm"
        const val KEY_PESO = "peso_kg"
        const val KEY_GENERO = "genero"
    }
}
