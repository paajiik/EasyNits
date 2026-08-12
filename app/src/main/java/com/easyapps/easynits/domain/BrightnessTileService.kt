package com.easyapps.easynits.domain

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.easyapps.easynits.R
import com.easyapps.easynits.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class BrightnessTileService : TileService() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var settingsRepository: SettingsRepository
    private var listeningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository.getInstance(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                updateTile(settings.isServiceActive)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        listeningJob?.cancel()
        listeningJob = null
    }

    override fun onClick() {
        super.onClick()
        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            val newState = !settings.isServiceActive
            settingsRepository.updateServiceActive(newState)
            
            if (newState) {
                startBrightnessService()
            } else {
                stopBrightnessService()
            }
        }
    }

    private fun updateTile(isActive: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isActive) getString(R.string.tile_active) else getString(R.string.tile_inactive)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.updateTile()
    }

    private fun startBrightnessService() {
        serviceScope.launch {
            val s = settingsRepository.settingsFlow.first()
            val intent = Intent(this@BrightnessTileService, BrightnessService::class.java)
            if (s.runInBackground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                startService(intent)
            }
        }
    }

    private fun stopBrightnessService() {
        val intent = Intent(this, BrightnessService::class.java)
        stopService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
