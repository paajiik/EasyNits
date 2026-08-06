package com.easyapps.easynits.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.easyapps.easynits.R
import com.easyapps.easynits.ui.theme.dimensions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val horizontalPadding = if (isLandscape) MaterialTheme.dimensions.xxLarge * 2 else MaterialTheme.dimensions.screenMarginCompact

    // Pre-calculate lists to avoid heavy logic in composition
    val iconList = remember(context) {
        val list = mutableListOf<Pair<String, Int>>()
        for (i in 1..21) {
            val resId = context.resources.getIdentifier("ic_launcher_v$i", "mipmap", context.packageName)
            if (resId != 0) {
                list.add("v$i" to resId)
            }
        }
        list
    }

    val languageList = remember(context) {
        val langCodes = listOf("system", "en", "de", "in", "es", "ar", "bn", "zh", "fr", "hi", "it", "ja", "ko", "pt", "ru")
        langCodes.map { code ->
            val nativeName = when(code) {
                "en" -> "English"
                "de" -> "Deutsch"
                "in" -> "Bahasa Indonesia"
                "es" -> "Español"
                "ar" -> "العربية"
                "bn" -> "বাংলা"
                "zh" -> "中文"
                "fr" -> "Français"
                "hi" -> "हिन्दी"
                "it" -> "Italiano"
                "ja" -> "日本語"
                "ko" -> "한국어"
                "pt" -> "Português"
                "ru" -> "Русский"
                else -> ""
            }
            val resId = when(code) {
                "en" -> R.string.lang_en
                "de" -> R.string.lang_de
                "in" -> R.string.lang_in
                "es" -> R.string.lang_es
                "ar" -> R.string.lang_ar
                "bn" -> R.string.lang_bn
                "zh" -> R.string.lang_zh
                "fr" -> R.string.lang_fr
                "hi" -> R.string.lang_hi
                "it" -> R.string.lang_it
                "ja" -> R.string.lang_ja
                "ko" -> R.string.lang_ko
                "pt" -> R.string.lang_pt
                "ru" -> R.string.lang_ru
                else -> R.string.lang_system
            }
            val translatedName = try { context.getString(resId) } catch (e: Exception) { code }
            val displayName = if (code == "system") translatedName else "$nativeName ($translatedName)"
            Triple(displayName, code, translatedName)
        }.sortedBy { if (it.second == "system") "" else it.third }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { 
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(start = if (isLandscape) MaterialTheme.dimensions.xxxLarge else MaterialTheme.dimensions.none)
                    ) 
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = if (isLandscape) MaterialTheme.dimensions.xxxLarge else MaterialTheme.dimensions.none)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back_btn_desc))
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        settings?.let { s ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding, vertical = MaterialTheme.dimensions.large),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.extraLarge)
            ) {
                // Appearance
                SettingsSection(title = stringResource(R.string.appearance_section), showCard = false) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaterialTheme.dimensions.small)
                    ) {
                        val options = listOf(
                            stringResource(R.string.theme_system),
                            stringResource(R.string.theme_light),
                            stringResource(R.string.theme_dark)
                        )
                        options.forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = s.themeMode == index,
                                onClick = { viewModel.updateThemeMode(index) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    activeContentColor = MaterialTheme.colorScheme.primary,
                                    activeBorderColor = MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Text(label)
                            }
                        }
                    }
                }

                // Theming
                SettingsSection(
                    title = stringResource(R.string.theming_section),
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.04f),
                    onClick = { viewModel.updateUseDynamicColor(!s.useDynamicColor) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.dimensions.large),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.large)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dynamic_color_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.dynamic_color_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = s.useDynamicColor,
                            onCheckedChange = { viewModel.updateUseDynamicColor(it) },
                            thumbContent = if (s.useDynamicColor) {
                                { Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
                            } else {
                                { Icon(Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize)) }
                            }
                        )
                    }

                    if (!s.useDynamicColor) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = MaterialTheme.dimensions.large))
                        Column(modifier = Modifier.padding(MaterialTheme.dimensions.large), verticalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.large)) {
                            ColorPaletteSection(
                                title = stringResource(R.string.custom_primary_color_title),
                                selectedColor = s.customPrimaryColor,
                                disabledColors = listOfNotNull(s.customBackgroundColor),
                                onColorSelected = { viewModel.updateCustomPrimaryColor(it) }
                            )
                            ColorPaletteSection(
                                title = stringResource(R.string.custom_text_color_title),
                                selectedColor = s.customTextColor,
                                disabledColors = listOfNotNull(s.customBackgroundColor),
                                onColorSelected = { viewModel.updateCustomTextColor(it) }
                            )
                            ColorPaletteSection(
                                title = stringResource(R.string.custom_background_color_title),
                                selectedColor = s.customBackgroundColor,
                                disabledColors = listOfNotNull(s.customPrimaryColor, s.customTextColor),
                                onColorSelected = { viewModel.updateCustomBackgroundColor(it) }
                            )
                        }
                    }
                }

                // App Icon
                SettingsSection(
                    title = stringResource(R.string.app_icon_section),
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
                ) {
                    if (iconList.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.large),
                            contentPadding = PaddingValues(horizontal = MaterialTheme.dimensions.large, vertical = MaterialTheme.dimensions.large)
                        ) {
                            items(iconList, key = { it.first }) { (id, resId) ->
                                IconOption(
                                    iconRes = resId,
                                    isSelected = s.appIcon == id || (s.appIcon == "default" && id == "v1"),
                                    onClick = { 
                                        try {
                                            viewModel.updateAppIcon(id, context.applicationContext)
                                        } catch (e: Exception) {
                                            // Safety catch
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Language
                var expanded by remember { mutableStateOf(false) }
                val currentLang = remember(s.language, languageList) {
                    languageList.find { it.second == s.language } ?: languageList.getOrNull(0) ?: Triple("System", "system", "System")
                }

                SettingsSection(
                    title = stringResource(R.string.language_section),
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.04f),
                    onClick = { expanded = true }
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.dimensions.large),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.large)
                        ) {
                            Icon(Icons.Rounded.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = currentLang.first,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Rounded.UnfoldMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            languageList.forEach { (name, code, _) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        viewModel.updateLanguage(code)
                                        expanded = false
                                    },
                                    trailingIcon = if (s.language == code) {
                                        { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }
                                    } else null
                                )
                            }
                        }
                    }
                }

                // Information
                SettingsSection(
                    title = stringResource(R.string.information_section),
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.04f),
                    onClick = onNavigateToAbout
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.about_easynits_item)) },
                        leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String, 
    showCard: Boolean = true, 
    containerColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.small)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = MaterialTheme.dimensions.none)
        )
        if (showCard) {
            val shape = MaterialTheme.shapes.extraLarge
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onClick != null) {
                            Modifier
                                .clip(shape)
                                .clickable { onClick() }
                        } else Modifier
                    ),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = containerColor ?: MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = shape
            ) {
                Column(
                    modifier = Modifier.padding(vertical = MaterialTheme.dimensions.none),
                    content = content
                )
            }
        }
else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }
}

@Composable
fun IconOption(iconRes: Int, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Icon Preview
        if (iconRes != 0) {
            AsyncImage(
                model = iconRes,
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
            )
        }

        // Outline Ring (Draw on top of the icon to ensure visibility)
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .border(4.dp, MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
        
        // Bottom Right Badge
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 4.dp, end = 4.dp)
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun ColorPaletteSection(
    title: String,
    selectedColor: Long?,
    disabledColors: List<Long> = emptyList(),
    onColorSelected: (Long?) -> Unit
) {
    val context = LocalContext.current
    val colors = listOf(
        0xFF000000, 0xFFFFFFFF, // Black, White
        0xFFD32F2F, 0xFFC2185B, 0xFF7B1FA2, 0xFF512DA8, 0xFF303F9F, // Red, Pink, Purple, DeepPurple, Indigo
        0xFF1976D2, 0xFF0288D1, 0xFF0097A7, 0xFF00796B, 0xFF388E3C, // Blue, LightBlue, Cyan, Teal, Green
        0xFF689F38, 0xFFAFB42B, 0xFFFBC02D, 0xFFFFA000, 0xFFF57C00, // LightGreen, Lime, Yellow, Amber, Orange
        0xFFE64A19, 0xFF5D4037, 0xFF455A64 // DeepOrange, Brown, BlueGrey
    )

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.small)) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.small)) {
            items(colors) { color ->
                val isDisabled = disabledColors.contains(color)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(color).copy(alpha = if (isDisabled) 0.2f else 1f))
                        .then(
                            if (selectedColor == color) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            } else Modifier
                        )
                        .clickable { 
                            if (isDisabled) {
                                android.widget.Toast.makeText(
                                    context, 
                                    context.getString(R.string.color_conflict_toast), 
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                onColorSelected(color) 
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedColor == color) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = if (color == 0xFFFFFFFFL) Color.Black else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    } else if (isDisabled) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Disabled",
                            tint = if (color == 0xFFFFFFFFL) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
