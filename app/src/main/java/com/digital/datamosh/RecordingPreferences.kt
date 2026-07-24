package com.digital.datamosh

import android.content.Context
import com.digital.datamosh.camera.FilterMode
import com.digital.datamosh.camera.RecordingConfiguration
import com.digital.datamosh.camera.VideoCodec
import com.digital.datamosh.camera.VideoResolution

internal class RecordingPreferences(context: Context) {
    private val preferences =
        context.getSharedPreferences("recording_options", Context.MODE_PRIVATE)

    fun loadConfiguration(): RecordingConfiguration = RecordingConfiguration(
        resolution = enumValueOrDefault(
            preferences.getString("resolution", null),
            VideoResolution.HD_720P,
        ),
        fps = preferences.getInt("fps", 30).takeIf { it in setOf(30, 60) } ?: 30,
        codec = enumValueOrDefault(
            preferences.getString("codec", null),
            VideoCodec.AVC,
        ),
    )

    fun loadFilter(): FilterMode = enumValueOrDefault(
        preferences.getString("filter", null),
        FilterMode.REGULAR,
    )

    fun saveConfiguration(configuration: RecordingConfiguration) {
        preferences.edit()
            .putString("resolution", configuration.resolution.name)
            .putInt("fps", configuration.fps)
            .putString("codec", configuration.codec.name)
            .apply()
    }

    fun saveFilter(filterMode: FilterMode) {
        preferences.edit().putString("filter", filterMode.name).apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        stored: String?,
        default: T,
    ): T = enumValues<T>().firstOrNull { it.name == stored } ?: default
}
