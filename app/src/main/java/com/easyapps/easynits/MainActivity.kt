package com.easyapps.easynits

import androidx.activity.SystemBarStyle
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.easyapps.easynits.ui.AboutScreen
import com.easyapps.easynits.ui.MainScreen
import com.easyapps.easynits.ui.SettingsScreen
import com.easyapps.easynits.ui.SettingsViewModel
import com.easyapps.easynits.ui.theme.EasyNitsTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        val viewModel: SettingsViewModel by lazy { 
            androidx.lifecycle.ViewModelProvider(this)[SettingsViewModel::class.java] 
        }

        splashScreen.setKeepOnScreenCondition {
            viewModel.settings.value == null
        }
        
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )

        // Initialize language from settings on first load
        lifecycleScope.launch {
            val settings = viewModel.settings.filterNotNull().first()
            val currentLocales = AppCompatDelegate.getApplicationLocales()
            val targetLocales = if (settings.language == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(settings.language)
            }
            if (currentLocales.toLanguageTags() != targetLocales.toLanguageTags()) {
                AppCompatDelegate.setApplicationLocales(targetLocales)
            }
        }
        
        setContent {
            val settings by viewModel.settings.collectAsState()

            EasyNitsTheme(
                themeMode = settings?.themeMode ?: 0,
                dynamicColor = settings?.useDynamicColor ?: true,
                customPrimary = settings?.customPrimaryColor,
                customBackground = settings?.customBackgroundColor,
                customText = settings?.customTextColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") {
                            MainScreen(
                                viewModel = viewModel,
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                                onNavigateToAbout = { navController.navigate("about") }
                            )
                        }
                        composable("about") {
                            AboutScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}

