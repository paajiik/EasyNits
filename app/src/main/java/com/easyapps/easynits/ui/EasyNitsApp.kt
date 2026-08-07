package com.easyapps.easynits.ui

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easyapps.easynits.R
import com.easyapps.easynits.data.ScheduledOffset
import com.easyapps.easynits.ui.theme.dimensions
import kotlin.math.roundToInt

import androidx.compose.ui.tooling.preview.Preview
import com.easyapps.easynits.ui.theme.EasyNitsTheme

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val lux by viewModel.currentLux.collectAsState()
    val nits by viewModel.currentNits.collectAsState()
    val uiOffset by viewModel.uiBrightnessOffset.collectAsState()
    val brightnessPercent by viewModel.currentBrightnessPercent.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    var hasPermission by remember { 
        mutableStateOf(viewModel.hasWriteSettingsPermission()) 
    }
    
    var hasNotificationPermission by remember { 
        mutableStateOf(viewModel.hasNotificationPermission()) 
    }

    var showAutoStartInfo by remember { mutableStateOf(false) }

    // Update permission state when app is resumed
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        hasPermission = viewModel.hasWriteSettingsPermission()
        hasNotificationPermission = viewModel.hasNotificationPermission()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> 
        hasNotificationPermission = isGranted
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val horizontalPadding = if (isLandscape) MaterialTheme.dimensions.xxxLarge else MaterialTheme.dimensions.screenMarginCompact

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = horizontalPadding)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.large)
    ) {
        // Centered Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MaterialTheme.dimensions.large, bottom = MaterialTheme.dimensions.none),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.padding(horizontal = MaterialTheme.dimensions.xxxLarge + 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.none)
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_monochrome),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.settings_icon_desc)
                )
            }
        }

        // Readings Section
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isLandscape) MaterialTheme.dimensions.xxLarge * 2 else MaterialTheme.dimensions.none),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.large)
        ) {
            val isServiceActive = settings?.isServiceActive == true
            ReadingCard(
                title = stringResource(R.string.sensor_lux_title),
                value = if (isServiceActive) lux.roundToInt().toString() else "0",
                unit = stringResource(R.string.flux_unit),
                modifier = Modifier.weight(1f),
                isLandscape = isLandscape
            )
            ReadingCard(
                title = stringResource(R.string.screen_nits_title),
                value = if (isServiceActive) nits.roundToInt().toString() else "0",
                unit = stringResource(R.string.nits_unit),
                modifier = Modifier.weight(1f),
                isLandscape = isLandscape
            )
        }

        if (!hasPermission) {
            AlertCard(
                title = stringResource(R.string.permission_required_title),
                description = stringResource(R.string.permission_required_desc),
                buttonText = stringResource(R.string.grant_btn),
                onClick = { viewModel.requestWriteSettingsPermission(context) }
            )
        }

        settings?.let { s ->
            if (s.runInBackground && !hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AlertCard(
                    title = stringResource(R.string.notifications_required_title),
                    description = stringResource(R.string.notifications_required_desc),
                    buttonText = stringResource(R.string.grant_btn),
                    onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                )
            }

            // Brightness Offset Section
            var isTimerExpanded by remember { mutableStateOf(false) }
            
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(
                    top = MaterialTheme.dimensions.extraLarge,
                    start = MaterialTheme.dimensions.extraLarge,
                    end = MaterialTheme.dimensions.extraLarge,
                    bottom = MaterialTheme.dimensions.small
                )) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.brightness_offset_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val offsetText = if (uiOffset == 0) "0" else "%+d".format(uiOffset)
                        Text(
                            text = stringResource(R.string.offset_label, offsetText),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    Text(
                        text = stringResource(R.string.current_brightness_percent, brightnessPercent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )

                    Spacer(modifier = Modifier.height(MaterialTheme.dimensions.large))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.small)
                    ) {
                        IconButton(
                            onClick = { viewModel.updateBrightnessOffset((uiOffset - 1).coerceAtLeast(-100)) },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.decrease_desc))
                        }
                        
                        Slider(
                            value = uiOffset.toFloat(),
                            onValueChange = { viewModel.updateBrightnessOffset(it.toInt()) },
                            valueRange = -100f..100f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                thumbColor = MaterialTheme.colorScheme.primary
                            )
                        )

                        IconButton(
                            onClick = { viewModel.updateBrightnessOffset((uiOffset + 1).coerceAtMost(100)) },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.increase_desc))
                        }
                    }

                    Spacer(modifier = Modifier.height(MaterialTheme.dimensions.small))

                    // Expandable Timer Section
                    Column(modifier = Modifier.fillMaxWidth()) {
                        IconButton(
                            onClick = { isTimerExpanded = !isTimerExpanded },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Icon(
                                if (isTimerExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = stringResource(R.string.toggle_timer_desc)
                            )
                        }

                        if (isTimerExpanded) {
                            var showAddDialog by remember { mutableStateOf(false) }
                            var editingSchedule by remember { mutableStateOf<ScheduledOffset?>(null) }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.small)
                            ) {
                                s.scheduledOffsets.forEach { schedule ->
                                    ScheduledOffsetItem(
                                        schedule = schedule,
                                        onEdit = { editingSchedule = schedule }
                                    )
                                }

                                TextButton(
                                    onClick = { showAddDialog = true },
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(MaterialTheme.dimensions.small))
                                    Text(stringResource(R.string.add_time_frame_btn))
                                }

                                if (showAddDialog) {
                                    TimeOffsetDialog(
                                        initialOffset = uiOffset,
                                        onDismiss = { showAddDialog = false },
                                        onConfirm = { startH, startM, endH, endM, offset, days ->
                                            viewModel.addScheduledOffset(startH, startM, endH, endM, offset, days)
                                            showAddDialog = false
                                        }
                                    )
                                }

                                if (editingSchedule != null) {
                                    TimeOffsetDialog(
                                        existingSchedule = editingSchedule,
                                        onDismiss = { editingSchedule = null },
                                        onConfirm = { startH, startM, endH, endM, offset, days ->
                                            viewModel.updateScheduledOffset(editingSchedule!!.id, startH, startM, endH, endM, offset, days)
                                            editingSchedule = null
                                        },
                                        onDelete = {
                                            viewModel.removeScheduledOffset(editingSchedule!!.id)
                                            editingSchedule = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Service Toggles
            ToggleCard(
                title = stringResource(R.string.activate_service_title),
                description = stringResource(R.string.activate_service_desc),
                checked = s.isServiceActive,
                onCheckedChange = { viewModel.toggleService(it) }
            )

            ToggleCard(
                title = stringResource(R.string.run_in_background_title),
                description = stringResource(R.string.run_in_background_desc),
                checked = s.runInBackground,
                onCheckedChange = { viewModel.toggleRunInBackground(it) }
            )

            ToggleCard(
                title = stringResource(R.string.auto_start_title),
                description = stringResource(R.string.auto_start_desc),
                checked = s.autoStart,
                onCheckedChange = { viewModel.toggleAutoStart(it) },
                onInfoClick = { showAutoStartInfo = true }
            )
        }
        
        if (showAutoStartInfo) {
            AlertDialog(
                onDismissRequest = { showAutoStartInfo = false },
                confirmButton = {
                    TextButton(onClick = { showAutoStartInfo = false }) {
                        Text(stringResource(R.string.got_it_btn))
                    }
                },
                title = { Text(stringResource(R.string.auto_start_info_title)) },
                text = {
                    Text(stringResource(R.string.auto_start_info_text))
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
fun ReadingCard(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    val aspectRatio = if (isLandscape) 3f else 1.6f
    OutlinedCard(
        modifier = modifier.aspectRatio(aspectRatio),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
fun ToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInfoClick: (() -> Unit)? = null
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .padding(MaterialTheme.dimensions.extraLarge)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title, 
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (onInfoClick != null) {
                        IconButton(
                            onClick = onInfoClick,
                            modifier = Modifier.size(24.dp).padding(start = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = "Info",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Switch(
                checked = checked, 
                onCheckedChange = onCheckedChange,
                thumbContent = if (checked) {
                    { Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    { Icon(Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize)) }
                }
            )
        }
    }
}

@Composable
fun AlertCard(
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.dimensions.extraLarge)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(MaterialTheme.dimensions.medium))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(buttonText)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeOffsetDialog(
    initialOffset: Int = 0,
    existingSchedule: ScheduledOffset? = null,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Int, Int, Int, List<Int>) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val startTimeState = rememberTimePickerState(
        initialHour = existingSchedule?.startHour ?: 8, 
        initialMinute = existingSchedule?.startMinute ?: 0, 
        is24Hour = true
    )
    val endTimeState = rememberTimePickerState(
        initialHour = existingSchedule?.endHour ?: 20, 
        initialMinute = existingSchedule?.endMinute ?: 0, 
        is24Hour = true
    )
    var offset by remember { mutableIntStateOf(existingSchedule?.offset ?: initialOffset) }
    var pickingStartTime by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 1=Sunday, 2=Monday, ..., 7=Saturday (Matching Calendar.DAY_OF_WEEK)
    var selectedDays by remember { 
        mutableStateOf(existingSchedule?.days?.toSet() ?: setOf(1, 2, 3, 4, 5, 6, 7)) 
    }
    val isEveryDaySelected = selectedDays.size == 7

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { 
                onConfirm(
                    startTimeState.hour, startTimeState.minute,
                    endTimeState.hour, endTimeState.minute,
                    offset,
                    selectedDays.toList().sorted()
                ) 
            }) {
                Text(stringResource(if (existingSchedule != null) R.string.save_btn else R.string.add_btn))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.delete_btn), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        },
        title = { Text(stringResource(if (existingSchedule != null) R.string.edit_scheduled_offset_title else R.string.add_scheduled_offset_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.dimensions.large)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isLandscape) Arrangement.Center else Arrangement.SpaceEvenly
                ) {
                    val spacing = if (isLandscape) MaterialTheme.dimensions.xxLarge else MaterialTheme.dimensions.none
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(end = spacing)
                    ) {
                        Text(stringResource(R.string.start_time_label), style = MaterialTheme.typography.labelSmall)
                        OutlinedCard(onClick = { pickingStartTime = true; showPicker = true }) {
                            Text(
                                text = "%02d:%02d".format(startTimeState.hour, startTimeState.minute),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.end_time_label), style = MaterialTheme.typography.labelSmall)
                        OutlinedCard(onClick = { pickingStartTime = false; showPicker = true }) {
                            Text(
                                text = "%02d:%02d".format(endTimeState.hour, endTimeState.minute),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }

                // Days Selection
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Every Day Button
                        FilterChip(
                            selected = isEveryDaySelected,
                            onClick = {
                                selectedDays = if (isEveryDaySelected) emptySet() else setOf(1, 2, 3, 4, 5, 6, 7)
                            },
                            label = { 
                                Text(
                                    text = stringResource(R.string.every_day).replace(" ", "\n"),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        lineHeight = 11.sp,
                                        textAlign = TextAlign.Center
                                    ),
                                    maxLines = 2
                                ) 
                            },
                            shape = CircleShape,
                            modifier = Modifier.padding(end = 4.dp)
                        )

                        VerticalDivider(modifier = Modifier.height(32.dp).padding(horizontal = 4.dp))

                        // Weekday Buttons
                        val weekdays = listOf(
                            stringResource(R.string.day_mon) to 2,
                            stringResource(R.string.day_tue) to 3,
                            stringResource(R.string.day_wed) to 4,
                            stringResource(R.string.day_thu) to 5,
                            stringResource(R.string.day_fri) to 6,
                            stringResource(R.string.day_sat) to 7,
                            stringResource(R.string.day_sun) to 1
                        )
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            weekdays.forEach { (label, dayValue) ->
                                val isSelected = dayValue in selectedDays
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary 
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable {
                                            selectedDays = if (isSelected) selectedDays - dayValue else selectedDays + dayValue
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label, 
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.offset_label, if (offset == 0) "0" else "%+d%%".format(offset)),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { offset = (offset - 1).coerceAtLeast(-100) },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.decrease_desc))
                    }
                    
                    Slider(
                        value = offset.toFloat(),
                        onValueChange = { offset = it.toInt() },
                        valueRange = -100f..100f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            thumbColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    IconButton(
                        onClick = { offset = (offset + 1).coerceAtMost(100) },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.increase_desc))
                    }
                }

                if (showPicker) {
                    TimePickerDialog(
                        onDismiss = { showPicker = false },
                        onConfirm = { showPicker = false }
                    ) {
                        TimePicker(state = if (pickingStartTime) startTimeState else endTimeState)
                    }
                }
            }
        }
    )
}

@Composable
fun TimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = !isLandscape),
        modifier = if (isLandscape) Modifier.fillMaxWidth(0.9f) else Modifier,
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
            }) {
                Text(stringResource(R.string.cancel_btn))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
            }) {
                Text(stringResource(R.string.ok_btn))
            }
        },
        text = { 
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isLandscape) Modifier.heightIn(max = 200.dp) else Modifier)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    )
}

@Composable
fun ScheduledOffsetItem(
    schedule: ScheduledOffset,
    onEdit: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Rounded.History, 
                    contentDescription = null, 
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    text = "%02d:%02d - %02d:%02d".format(
                        schedule.startHour, schedule.startMinute,
                        schedule.endHour, schedule.endMinute
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                
                Text(
                    text = if (schedule.offset == 0) "0" else "%+d%%".format(schedule.offset),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                IconButton(onClick = onEdit, modifier = Modifier.size(MaterialTheme.dimensions.xxLarge)) {
                    Icon(
                        Icons.Rounded.Edit, 
                        contentDescription = stringResource(R.string.settings_icon_desc), 
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            val daysText = if (schedule.days.size == 7) stringResource(R.string.every_day) 
                           else {
                               val labels = mapOf(
                                   2 to stringResource(R.string.day_mon),
                                   3 to stringResource(R.string.day_tue),
                                   4 to stringResource(R.string.day_wed),
                                   5 to stringResource(R.string.day_thu),
                                   6 to stringResource(R.string.day_fri),
                                   7 to stringResource(R.string.day_sat),
                                   1 to stringResource(R.string.day_sun)
                               )
                               // Sort by the desired order: MON (2) to SUN (1)
                               val monToSunOrder = listOf(2, 3, 4, 5, 6, 7, 1)
                               schedule.days
                                   .sortedBy { monToSunOrder.indexOf(it) }
                                   .mapNotNull { labels[it] }
                                   .joinToString(", ")
                           }
            Text(
                text = daysText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = MaterialTheme.dimensions.extraLarge + 4.dp)
            )
        }
    }
}

@Preview(showBackground = true, device = "spec:width=411dp,height=891dp")
@Composable
fun MainScreenPreview() {
    EasyNitsTheme {
        MainScreen()
    }
}

