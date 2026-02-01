package net.typeblob.socks

import android.content.Context

object ProfileManager {
    private const val PREFS_NAME = "slipstream_profiles"
    private const val KEY_CURRENT_PROFILE = "current_profile"
    private const val KEY_PROFILE_PREFIX = "profile_"
    
    var currentProfile: Profile = Profile()
        private set
    
    fun loadCurrentProfile(context: Context): Profile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val profileName = prefs.getString(KEY_CURRENT_PROFILE, "Default") ?: "Default"
        
        currentProfile = Profile(
            name = profileName,
            domain = prefs.getString("${KEY_PROFILE_PREFIX}${profileName}_domain", "") ?: "",
            resolvers = prefs.getString("${KEY_PROFILE_PREFIX}${profileName}_resolvers", "") ?: "",
            port = prefs.getInt("${KEY_PROFILE_PREFIX}${profileName}_port", 1081)
        )
        
        return currentProfile
    }
    
    fun saveProfile(context: Context, profile: Profile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("${KEY_PROFILE_PREFIX}${profile.name}_domain", profile.domain)
            putString("${KEY_PROFILE_PREFIX}${profile.name}_resolvers", profile.resolvers)
            putInt("${KEY_PROFILE_PREFIX}${profile.name}_port", profile.port)
            apply()
        }
        currentProfile = profile
    }
    
    fun setCurrentProfile(context: Context, profileName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CURRENT_PROFILE, profileName).apply()
    }
}