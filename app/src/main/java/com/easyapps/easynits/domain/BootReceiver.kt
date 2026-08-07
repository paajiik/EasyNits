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
        Log.d("EasyNits_Debug", "BootReceiver: action=$action")
        
        val isBootEvent = action == Intent.ACTION_BOOT_COMPLETED || 
                         action == Intent.ACTION_USER_PRESENT ||
                         action == "android.intent.action.QUICKBOOT_POWERON" ||
                         action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (isBootEvent) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settings = SettingsRepository.getInstance(context).settingsFlow.first()
                    Log.d("EasyNits_Debug", "BootReceiver: active=${settings.isServiceActive}, autoStart=${settings.autoStart}")
                    
                    if (settings.isServiceActive && settings.autoStart) {
                        val serviceIntent = Intent(context, BrightnessService::class.java).apply {
                            putExtra("FROM_BOOT", true)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("EasyNits_Debug", "BootReceiver: Error starting service", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
