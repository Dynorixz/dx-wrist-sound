package dev.dx.wristsound.wear

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.*
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.dx.wristsound.shared.*
import dev.dx.wristsound.shared.R as SharedR
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var model: WatchViewModel
    private var permissionRevision by mutableIntStateOf(0)
    private val overlayChanges = AppOpsManager.OnOpChangedListener { operation, changedPackage ->
        if (operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && changedPackage == packageName) {
            runOnUiThread { permissionRevision++ }
        }
    }
    private val policyAccessChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            permissionRevision++
            model.refresh()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The display scheduler and the watch's power mode choose the actual refresh rate.
        window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
        PhoneLossMonitor.createChannel(this)
        model = ViewModelProvider(this)[WatchViewModel::class.java]
        setContent {
            MaterialTheme {
                val state by model.ui.collectAsStateWithLifecycle()
                val accesses = remember(permissionRevision, state.sensor.permissionNeeded) { PermissionGuide.items(this, true, state.sensor.permissionNeeded) }
                val overlayGranted = remember(permissionRevision) { Settings.canDrawOverlays(this) }
                val dndGranted = remember(permissionRevision) { getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }
                val links = remember { AppLinks.author(this) }
                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.setEnabled(true) }
                val sensorPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) model.setEnabled(true) else model.permissionDenied() }
                AppScaffold {
                    WatchScreen(state, accesses, links, model, overlayGranted, dndGranted,
                        onRefreshPermissions = { permissionRevision++; model.refresh() },
                        onPermission = { PermissionGuide.open(this@MainActivity, it) }, onPreviewPopup = {
                        // Check again at use time: ADB can grant or revoke access while this screen is open.
                        val granted = Settings.canDrawOverlays(this@MainActivity)
                        permissionRevision++
                        if (granted) model.testPopup()
                        granted
                    }, onToggle = {
                        val needed = state.sensor.permissionNeeded
                        if (!state.settings.enabled && needed != null && ContextCompat.checkSelfPermission(this@MainActivity, needed) != PackageManager.PERMISSION_GRANTED) sensorPermission.launch(needed)
                        else if (!state.settings.enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else model.setEnabled(!state.settings.enabled)
                    })
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        runCatching { getSystemService(AppOpsManager::class.java)
            .startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, overlayChanges) }
        ContextCompat.registerReceiver(this, policyAccessChanges,
            IntentFilter(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStop() {
        runCatching { getSystemService(AppOpsManager::class.java).stopWatchingMode(overlayChanges) }
        runCatching { unregisterReceiver(policyAccessChanges) }
        super.onStop()
    }
    override fun onResume() { super.onResume(); permissionRevision++; model.refresh() }
}

@Composable
private fun WatchScreen(state: WatchUiState, permissions: List<PermissionItem>, links: List<AppLink>, model: WatchViewModel,
    overlayGranted: Boolean, dndGranted: Boolean, onRefreshPermissions: () -> Unit,
    onPermission: (PermissionDestination) -> Unit, onPreviewPopup: () -> Boolean, onToggle: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var overlayReturnPage by rememberSaveable { mutableIntStateOf(3) }
    var showOverlayCommands by rememberSaveable { mutableStateOf(false) }
    var soundReturnPage by rememberSaveable { mutableIntStateOf(3) }
    var showSoundCommands by rememberSaveable { mutableStateOf(false) }
    val openOverlayHelp = {
        if (page != 6) overlayReturnPage = page
        onRefreshPermissions()
        page = 6
    }
    val configurePermission: (PermissionDestination) -> Unit = { destination ->
        when (destination) {
            PermissionDestination.OVERLAY -> openOverlayHelp()
            PermissionDestination.DND -> {
                if (page != 7) soundReturnPage = page
                onRefreshPermissions()
                page = 7
            }
            else -> onPermission(destination)
        }
    }
    val previewPopup = { if (!onPreviewPopup()) openOverlayHelp() }
    val backPage = when (page) { 6 -> overlayReturnPage; 7 -> soundReturnPage; else -> 0 }
    BackHandler(page != 0) { page = backPage }
    val events = if (page == 4) model.events.collectAsStateWithLifecycle(emptyList()).value else emptyList()
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val title = when (page) { 1 -> "Оповещения"; 2 -> "Настройки"; 3 -> "Разрешения"; 4 -> "Диагностика"; 5 -> "О приложении"; 6 -> "Окно над циферблатом"; 7 -> "Доступ к звуку"; else -> "DX • Wrist Sound" }
    key(page) {
        val columnState = rememberTransformingLazyColumnState()
        val spec = rememberTransformationSpec()
        ScreenScaffold(scrollState = columnState) { padding ->
            TransformingLazyColumn(state = columnState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = padding.calculateTopPadding() + 32.dp, bottom = padding.calculateBottomPadding() + 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                item(key = "title") {
                    ListHeader(modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text(title, textAlign = TextAlign.Center) }
                }
                when (page) {
                    0 -> {
                        if (state.info.phoneLost) item(key = "lost") {
                            WatchPanel {
                                Image(painterResource(SharedR.drawable.lost_phone), "Потерянный телефон", Modifier.size(52.dp))
                                Text("Вы забыли телефон", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                                Button(onClick = model::dismissLoss, modifier = Modifier.fillMaxWidth()) { Text("Понятно") }
                            }
                        }
                        item(key = "status") {
                            WatchPanel {
                                Text(wristLabel(state.info.wrist), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                                WatchNote(if (state.info.connected) "● Телефон рядом" else "○ Телефон не подключён")
                                Text(if (!state.settings.enabled) "Автоматизация выключена" else if (state.info.running) "Работает в фоне" else "Ожидает восстановления", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                            }
                        }
                        item(key = "toggle") {
                            Button(onClick = onToggle, enabled = state.sensor.available || state.settings.enabled, modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text(if (state.settings.enabled) "Остановить" else "Включить") }
                        }
                        state.soundProblem?.let { problem -> item(key = "sound_problem") {
                            WatchPanel {
                                Text(problem.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                WatchNote("Это восстановление после старой версии. Можно включить звук вручную в настройках часов.")
                                Button(onClick = { configurePermission(PermissionDestination.SOUND) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Включить звук часов")
                                }
                            }
                        } }
                        if (!state.sensor.available || state.sensor.error.isNotBlank() || state.info.error.isNotBlank()) item(key = "error") {
                            Text(state.sensor.error.ifBlank { state.info.error }.ifBlank { "На часах нет датчика ношения" }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }
                        items(listOf(1 to "Оповещения", 2 to "Настройки", 3 to "Разрешения", 4 to "Диагностика", 5 to "О приложении"), key = { it.first }, contentType = { "menu" }) { (destination, label) ->
                            FilledTonalButton(onClick = { page = destination }, modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text(label) }
                        }
                    }
                    1 -> {
                        item(key = "enabled") { Button(onClick = { model.changeAlerts { it.copy(phoneLossAlert = !it.phoneLossAlert) } }, modifier = Modifier.fillMaxWidth()) { Text(if (state.settings.phoneLossAlert) "Оповещение: вкл." else "Оповещение: выкл.") } }
                        item(key = "voice") { WatchChoice("Русский голос", state.settings.phoneLossVoice) { model.changeAlerts { it.copy(phoneLossVoice = true) } } }
                        item(key = "chime") { WatchChoice("Мелодия", !state.settings.phoneLossVoice) { model.changeAlerts { it.copy(phoneLossVoice = false) } } }
                        item(key = "delay") {
                            WatchPanel {
                                WatchField("Подтвердить потерю", "${state.settings.phoneLossSeconds} секунд")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { model.changeAlerts { it.copy(phoneLossSeconds = (it.phoneLossSeconds - 15).coerceAtLeast(15)) } }, modifier = Modifier.weight(1f), enabled = state.settings.phoneLossSeconds > 15) { Text("−15") }
                                    TextButton(onClick = { model.changeAlerts { it.copy(phoneLossSeconds = (it.phoneLossSeconds + 15).coerceAtMost(120)) } }, modifier = Modifier.weight(1f), enabled = state.settings.phoneLossSeconds < 120) { Text("+15") }
                                }
                            }
                        }
                        item(key = "preview") { Button(onClick = model::testAlert, modifier = Modifier.fillMaxWidth()) { Text("Послушать") } }
                        if (state.voiceStatus.isNotBlank()) item { WatchField("Звук", state.voiceStatus) }
                        item(key = "overlay") { FilledTonalButton(onClick = { configurePermission(PermissionDestination.OVERLAY) }, modifier = Modifier.fillMaxWidth()) { Text("Над циферблатом") } }
                        item(key = "popup_preview") { FilledTonalButton(onClick = previewPopup, modifier = Modifier.fillMaxWidth()) { Text("Проверить окно") } }
                        item { WatchNote("Когда часы на руке, появится предупреждение. Голос: «Вы забыли телефон». Для речи и мелодии нужна громкость медиа. Выбор синхронизируется с телефоном.") }
                        if (state.settings.alertSettingsPending) item { WatchNote("Выбор сохранён. Отправим на телефон при подключении.") }
                    }
                    2 -> {
                        item { WatchField("Подтверждение датчика", "Надели: ${state.settings.debounceMillis / 1000} с\nСняли: ${state.settings.offDebounceMillis / 1000} с") }
                        item { WatchNote("Задержки и режим звука телефона меняются в приложении на телефоне. Короткие ошибки датчика отбрасываются.") }
                        item { FilledTonalButton(onClick = { onPermission(PermissionDestination.BACKGROUND_WATCH) }, modifier = Modifier.fillMaxWidth()) { Text("Работа ночью") } }
                        item { FilledTonalButton(onClick = { onPermission(PermissionDestination.SOUND) }, modifier = Modifier.fillMaxWidth()) { Text("Громкость часов") } }
                        item { WatchNote("Выбранные звук и мелодия часов сохраняются. DX автоматически меняет звук только на телефоне.") }
                        item { Button(onClick = { model.refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Синхронизировать") } }
                        item { WatchNote("${if (state.settings.startOnBoot) "Автозапуск включён" else "Автозапуск выключен"}. Фоновая проверка восстанавливает службу; пробуждение экрана обновляет датчик.") }
                    }
                    3 -> items(permissions, key = { it.destination.name }, contentType = { "permission" }) { access ->
                        WatchPanel {
                            WatchField(access.title, access.status)
                            WatchNote(access.explanation)
                            TextButton(onClick = { configurePermission(access.destination) }, modifier = Modifier.fillMaxWidth()) { Text(if (access.destination == PermissionDestination.OVERLAY) "Проверить доступ" else "Настроить") }
                        }
                    }
                    4 -> {
                        item { WatchField("Датчик", state.sensor.name) }
                        item { WatchField("Пробуждает процесс", if (state.sensor.wakeUp) "Да · wake-up" else "Нет · non wake-up") }
                        item { WatchField("Состояние", state.info.wrist.name) }
                        item { WatchField("Связь", if (state.info.connected) "Подключён" else "Отключён") }
                        item { WatchField("Последняя отправка", time(state.info.lastUpdate, formatter)) }
                        item { WatchField("Heartbeat", time(state.info.lastHeartbeat, formatter)) }
                        item { Button(onClick = { model.refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Отправить состояние") } }
                        item { Text("Последние события", style = MaterialTheme.typography.titleSmall) }
                        items(events.size, key = { "event_$it" }, contentType = { "event" }) { index -> val event = events[index]; WatchField(time(event.time, formatter), event.message) }
                    }
                    5 -> {
                        item { WatchField("DX • Wrist Sound", "Версия 1.2.4 · dynorixz") }
                        item { WatchNote("Управление звуком телефона по датчику ношения часов. Поддержка добровольна.") }
                        items(links, key = { it.url }, contentType = { "link" }) { link ->
                            Button(onClick = { model.openOnPhone(link) }, modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text(link.title) }
                        }
                        item { WatchNote("Ссылка откроется через уведомление на телефоне.") }
                        if (state.linkStatus.isNotBlank()) item { WatchNote(state.linkStatus) }
                    }
                    6 -> {
                        item(key = "overlay_status") {
                            WatchPanel {
                                WatchField("Фактический доступ", if (overlayGranted) "Разрешено" else "Нужен доступ")
                                WatchNote(if (overlayGranted) "Доступ уже выдан. Повторно разрешать его не нужно." else "Без этого доступа предупреждение останется системным уведомлением.")
                            }
                        }
                        if (overlayGranted) item(key = "overlay_test") {
                            Button(onClick = previewPopup, modifier = Modifier.fillMaxWidth()) { Text("Проверить окно") }
                        }
                        item(key = "overlay_refresh") {
                            FilledTonalButton(onClick = onRefreshPermissions, modifier = Modifier.fillMaxWidth()) { Text("Обновить статус") }
                        }
                        item(key = "overlay_explanation") {
                            WatchNote("На некоторых часах, включая Samsung, доступ нельзя выдать через меню. Системное сообщение «Недоступно» относится к экрану настройки. Если выше написано «Разрешено», доступ уже есть.")
                        }
                        if (overlayGranted) item(key = "overlay_help_toggle") {
                            TextButton(onClick = { showOverlayCommands = !showOverlayCommands }, modifier = Modifier.fillMaxWidth()) { Text(if (showOverlayCommands) "Скрыть инструкцию" else "Инструкция ADB") }
                        }
                        if (!overlayGranted || showOverlayCommands) {
                            item(key = "overlay_adb_setup") {
                                WatchField("Как выдать через ADB", "На часах включите параметры разработчика и беспроводную отладку. Подключите компьютер к той же Wi-Fi сети. Для нового компьютера сначала выполните сопряжение ADB с кодом с часов.")
                            }
                            item(key = "overlay_adb_address") {
                                WatchNote("Замените IP:PORT адресом и портом подключения с главного экрана беспроводной отладки часов. Порт сопряжения используется отдельно.")
                            }
                            item(key = "overlay_adb_connect") { WatchCommand("Подключить часы", "adb connect IP:PORT") }
                            item(key = "overlay_adb_grant") {
                                WatchCommand("Выполнить одной строкой", "adb -s IP:PORT shell appops set dev.dx.wristsound SYSTEM_ALERT_WINDOW allow")
                            }
                            item(key = "overlay_adb_done") {
                                WatchNote("Команда должна быть адресована часам. Вернитесь сюда и нажмите «Обновить статус», затем «Проверить окно». После настройки можно выключить беспроводную отладку.")
                            }
                        }
                    }
                    7 -> {
                        item(key = "sound_access_status") {
                            WatchPanel {
                                WatchField("Фактический доступ", if (dndGranted) "Разрешён" else "Нужен доступ")
                                WatchNote("Этот доступ может понадобиться только для завершения восстановления звука после предыдущей версии. В обычной работе DX сохраняет выбранный звук часов.")
                            }
                        }
                        if (!dndGranted) item(key = "sound_access_settings") {
                            Button(onClick = { onPermission(PermissionDestination.DND) }, modifier = Modifier.fillMaxWidth()) { Text("Открыть разрешение") }
                        }
                        item(key = "sound_access_refresh") {
                            FilledTonalButton(onClick = onRefreshPermissions, modifier = Modifier.fillMaxWidth()) { Text("Проверить снова") }
                        }
                        item(key = "sound_access_explanation") {
                            WatchNote("Доступ к окнам над циферблатом выдаётся отдельно. Если системное меню часов не позволяет выдать доступ «Не беспокоить», его можно выдать через ADB с компьютера.")
                        }
                        if (dndGranted) item(key = "sound_help_toggle") {
                            TextButton(onClick = { showSoundCommands = !showSoundCommands }, modifier = Modifier.fillMaxWidth()) { Text(if (showSoundCommands) "Скрыть инструкцию" else "Инструкция ADB") }
                        }
                        if (!dndGranted || showSoundCommands) {
                            item(key = "sound_adb_setup") {
                                WatchNote("Включите Wi-Fi и беспроводную отладку на часах. Для нового компьютера выполните сопряжение ADB. Замените IP:PORT адресом и портом подключения часов с главного экрана отладки.")
                            }
                            item(key = "sound_adb_connect") { WatchCommand("Подключить часы", "adb connect IP:PORT") }
                            item(key = "sound_adb_grant") { WatchCommand("Выдать доступ одной строкой", "adb -s IP:PORT shell cmd notification allow_dnd dev.dx.wristsound") }
                            item(key = "sound_adb_done") {
                                WatchNote("Команду выполните именно для часов. Вернитесь в DX и нажмите «Проверить снова». Разрешение проверяется заново, переключение звука повторяется. После настройки беспроводную отладку можно выключить.")
                            }
                        }
                        item(key = "sound_settings") {
                            FilledTonalButton(onClick = { onPermission(PermissionDestination.SOUND) }, modifier = Modifier.fillMaxWidth()) { Text("Настройки звука") }
                        }
                    }
                }
                if (page != 0) item(key = "back") { TextButton(onClick = { page = backPage }, modifier = Modifier.fillMaxWidth()) { Text(if (page == 6 || page == 7) "Назад" else "На главную") } }
            }
        }
    }
}

@Composable private fun WatchPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(22.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
@Composable private fun WatchChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer, contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) { Text(if (selected) "✓ $label" else label) }
}
@Composable private fun WatchField(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Text(value, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}
@Composable private fun WatchNote(value: String) { Text(value, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
@Composable private fun WatchCommand(label: String, command: String) {
    WatchPanel {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Text(command, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
private fun time(value: Long, formatter: SimpleDateFormat) = if (value == 0L) "—" else formatter.format(Date(value))
private fun wristLabel(state: WristState) = when (state) { WristState.ON_WRIST -> "Часы на руке"; WristState.OFF_WRIST -> "Часы сняты"; WristState.UNKNOWN -> "Проверяем датчик" }
