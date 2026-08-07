package com.easyapps.easynits.domain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.easyapps.easynits.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val isBootEvent = action == Intent.ACTION_BOOT_COMPLETED || 
                         action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                         action == "android.intent.action.QUICKBOOT_POWERON" ||
                         action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (isBootEvent) {
            Log.d("BootReceiver", "Boot event received: $action")
            val pendingResult = goAsync()
            val repository = SettingsRepository(context)
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settings = repository.settingsFlow.first()
                    if (settings.autoStart && settings.isServiceActive) {
                        Log.d("BootReceiver", "Starting BrightnessService (autoStart=true)")
                        val serviceIntent = Intent(context, BrightnessService::class.java)
                        
                        if (settings.runInBackground) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        } else {
                            context.startService(serviceIntent)
                        }
                    } else {
                        Log.d("BootReceiver", "Service not started (autoStart=${settings.autoStart}, active=${settings.isServiceActive})")
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error in onReceive async: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
