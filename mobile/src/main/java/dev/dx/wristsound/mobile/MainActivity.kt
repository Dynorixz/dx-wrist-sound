package dev.dx.wristsound.mobile

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dx.wristsound.shared.*
import dev.dx.wristsound.shared.R as SharedR
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var model: PhoneViewModel
    private var permissionRevision by mutableIntStateOf(0)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { if (::model.isInitialized) model.refreshVolume() }
    }
    private val ringer = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { model.refreshVolume() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[PhoneViewModel::class.java]
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme(primary = Color(0xFFA8D5BE), secondary = Color(0xFFBACCBF))
            else lightColorScheme(primary = Color(0xFF38634E), secondary = Color(0xFF516456))
            MaterialTheme(colorScheme = colors) {
                val state by model.ui.collectAsStateWithLifecycle()
                val accesses = remember(permissionRevision, state.policyAccess) { PermissionGuide.items(this, false) }
                val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    if (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        model.setEnabled(true)
                    } else model.recordPermissionError()
                }
                PhoneScreen(state, model.maximum, accesses, AppLinks.author(this),
                    onPermission = { PermissionGuide.open(this, it) },
                    onLink = { AppLinks.open(this, it) },
                    onEnable = { enabled ->
                        val needed = buildList {
                            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (enabled && needed.isNotEmpty()) permissions.launch(needed.toTypedArray()) else model.setEnabled(enabled)
                    },
                    onSettings = model::changeSettings,
                    onPolicy = { openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
                    onBattery = { openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
                    onSound = { openSettings(Settings.ACTION_SOUND_SETTINGS) },
                    onRefresh = model::refresh,
                    onClear = { model.clearLog() },
                    onExport = { events ->
                        val text = events.joinToString("\n") { "${formatTime(it.time)} ${it.message}" }
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Журнал DX • Wrist Sound"))
                    },
                )
            }
        }
    }
    override fun onResume() {
        super.onResume(); permissionRevision++; model.refresh()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        ContextCompat.registerReceiver(this, ringer, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onPause() {
        contentResolver.unregisterContentObserver(observer); unregisterReceiver(ringer)
        super.onPause()
    }
    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneScreen(
    state: PhoneUiState, maximum: Int, permissions: List<PermissionItem>, links: List<AppLink>,
    onPermission: (PermissionDestination) -> Unit, onLink: (AppLink) -> Unit, onEnable: (Boolean) -> Unit,
    onSettings: ((AppSettings) -> AppSettings) -> Any,
    onPolicy: () -> Unit, onBattery: () -> Unit, onSound: () -> Unit, onRefresh: () -> Unit,
    onClear: () -> Unit, onExport: (List<EventEntry>) -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pages = listOf("Главная", "Настройки", "Диагностика", "О приложении")
    val icons = listOf(SharedR.drawable.ic_wrist, SharedR.drawable.ic_settings, SharedR.drawable.ic_diagnostics, SharedR.drawable.ic_info)
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DX • Wrist Sound", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("ЗВУК СЛЕДУЕТ ЗА ВАМИ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }) },
        bottomBar = { NavigationBar { pages.forEachIndexed { index, title ->
            NavigationBarItem(selected = page == index, onClick = { page = index }, label = { Text(title) },
                icon = { Icon(painterResource(icons[index]), title) })
        } } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.policyAccess) item {
                InfoCard("Разрешите изменение звука", "Для перехода в беззвучный режим и обратно Android требует специальный доступ «Не беспокоить».") {
                    Button(onClick = onPolicy) { Text("Открыть настройки доступа") }
                }
            }
            if (state.info.error.isNotBlank()) item { InfoCard("Нужно внимание", state.info.error) { TextButton(onClick = onRefresh) { Text("Проверить снова") } } }
            if (state.dndActive) item { InfoCard("Системный режим «Не беспокоить»", "Этот режим может подавлять звонки даже при максимальной громкости. Проверьте его настройки.") { TextButton(onClick = onSound) { Text("Настройки звука") } } }
            when (page) {
                0 -> {
                    item {
                        Card(shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Icon(painterResource(SharedR.drawable.ic_wrist), null, Modifier.size(40.dp))
                                Text(state.info.peerName, style = MaterialTheme.typography.titleLarge)
                                Text(if (state.info.connected) "Подключено рядом" else "Нет локального соединения", style = MaterialTheme.typography.bodyMedium)
                                Text(wristTitle(state.info.wrist), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                                Text(if (state.info.phase == AutomationPhase.FALLBACK_SOUND_ON) "Защитный режим: звук включён" else "Обновление: ${formatTime(state.info.lastUpdate)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item { Panel { ToggleRow("Автоматизация", if (state.info.running) "Работает в фоне" else "Мониторинг остановлен", state.settings.enabled, onEnable) } }
                    item { Panel {
                        Text("Громкость телефона", style = MaterialTheme.typography.titleMedium)
                        Text("${state.sound.volume} / $maximum", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
                        LinearProgressIndicator(progress = { if (maximum > 0) state.sound.volume.toFloat() / maximum else 0f }, modifier = Modifier.fillMaxWidth())
                        Text(modeTitle(state.sound.ringerMode), style = MaterialTheme.typography.bodyMedium)
                    } }
                    item { Panel {
                        Rule("На руке", onRule(state.settings))
                        HorizontalDivider()
                        Rule("Сняты с руки", offRule(state.settings))
                        Text("Часы: выбранный звук и мелодия сохраняются", style = MaterialTheme.typography.bodySmall)
                    } }
                    item { InfoCard("Звонки и уведомления на часах", "Включите уведомления и звонки в Galaxy Wearable / приложении-компаньоне часов. Проверьте вибрацию на самих часах.") {
                        TextButton(onClick = onSound) { Text("Звук телефона") }
                    } }
                    if (state.settings.enabled && !state.info.running) item { Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Возобновить мониторинг") } }
                }
                1 -> {
                    item { Panel { ToggleRow("Автоматизация", "Уведомление показывается, пока включена", state.settings.enabled, onEnable) } }
                    item { Panel {
                        Text("Когда часы на руке", style = MaterialTheme.typography.titleLarge)
                        Choice("Без звука · 0%", state.settings.onMode == OnWristMode.SILENT) { onSettings { it.copy(onMode = OnWristMode.SILENT) } }
                        Choice("Вибрация телефона", state.settings.onMode == OnWristMode.VIBRATE) { onSettings { it.copy(onMode = OnWristMode.VIBRATE) } }
                        Choice("Своя громкость", state.settings.onMode == OnWristMode.CUSTOM) { onSettings { it.copy(onMode = OnWristMode.CUSTOM) } }
                        if (state.settings.onMode == OnWristMode.CUSTOM) PercentSlider(state.settings.onPercent) { value -> onSettings { it.copy(onPercent = value) } }
                        Text("Звук часов выбирается в их системных настройках. DX меняет режим звука только на телефоне.", style = MaterialTheme.typography.bodySmall)
                    } }
                    item { Panel {
                        Text("Когда часы сняты", style = MaterialTheme.typography.titleLarge)
                        Choice("Максимальная громкость", state.settings.offMode == OffWristMode.MAXIMUM) { onSettings { it.copy(offMode = OffWristMode.MAXIMUM) } }
                        Choice("Предыдущая громкость", state.settings.offMode == OffWristMode.RESTORE) { onSettings { it.copy(offMode = OffWristMode.RESTORE) } }
                        Choice("Своя громкость", state.settings.offMode == OffWristMode.CUSTOM) { onSettings { it.copy(offMode = OffWristMode.CUSTOM) } }
                        if (state.settings.offMode == OffWristMode.CUSTOM) PercentSlider(state.settings.offPercent) { value -> onSettings { it.copy(offPercent = value) } }
                    } }
                    item { Panel {
                        Text("Защита при потере связи", style = MaterialTheme.typography.titleLarge)
                        ToggleRow("Включить звук", "При недоступности часов — максимум", state.settings.fallbackEnabled) { value -> onSettings { it.copy(fallbackEnabled = value) } }
                        IntegerSlider("Отключение", state.settings.disconnectSeconds, 5..120, "сек") { value -> onSettings { it.copy(disconnectSeconds = value) } }
                        IntegerSlider("Без heartbeat", state.settings.heartbeatTimeoutSeconds / 60, 5..30, "мин") { value -> onSettings { it.copy(heartbeatTimeoutSeconds = value * 60) } }
                        Text("В глубоком сне Android может задерживать таймеры. Heartbeat отправляется примерно каждые 2 минуты.", style = MaterialTheme.typography.bodySmall)
                    } }
                    item { Panel {
                        Text("Защита от ложных срабатываний", style = MaterialTheme.typography.titleLarge)
                        IntegerSlider("Часы надели", (state.settings.debounceMillis / 1000).toInt(), 1..10, "сек") { value -> onSettings { it.copy(debounceMillis = value * 1000L) } }
                        IntegerSlider("Часы сняли", (state.settings.offDebounceMillis / 1000).toInt(), 3..15, "сек") { value -> onSettings { it.copy(offDebounceMillis = value * 1000L) } }
                        Text("Короткие ошибки датчика отбрасываются. Перед защитным переключением повторно проверяется локальная связь.", style = MaterialTheme.typography.bodySmall)
                        ToggleRow("Запуск после перезагрузки", "На телефоне и часах", state.settings.startOnBoot) { value -> onSettings { it.copy(startOnBoot = value) } }
                        TextButton(onClick = onBattery) { Text("Настройки энергосбережения") }
                    } }
                    item { Panel {
                        Text("Вы забыли телефон", style = MaterialTheme.typography.titleLarge)
                        ToggleRow("Предупреждение на часах", "Окно над циферблатом: разрешите показ поверх приложений на часах", state.settings.phoneLossAlert) { value -> onSettings { it.copy(phoneLossAlert = value) } }
                        Choice("Русский голос: «Вы забыли телефон»", state.settings.phoneLossVoice) { onSettings { it.copy(phoneLossVoice = true) } }
                        Choice("Короткая мелодия", !state.settings.phoneLossVoice) { onSettings { it.copy(phoneLossVoice = false) } }
                        Text("Звук использует громкость медиа часов. Проверить выбранное оповещение можно на часах → Оповещения.", style = MaterialTheme.typography.bodySmall)
                        IntegerSlider("Потеря телефона", state.settings.phoneLossSeconds, 15..120, "сек") { value -> onSettings { it.copy(phoneLossSeconds = value) } }
                        Text("Одна тревога на обрыв связи. Для следующей нужен устойчивый reconnect в течение минуты.", style = MaterialTheme.typography.bodySmall)
                    } }
                    item { Panel {
                        Text("Разрешения и доступы", style = MaterialTheme.typography.titleLarge)
                        permissions.forEach { item ->
                            Diagnostic(item.title, item.status)
                            Text(item.explanation, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onPermission(item.destination) }) { Text("Открыть настройки") }
                        }
                    } }
                    if (state.peers.isNotEmpty()) item { Panel {
                        Text("Часы для автоматизации", style = MaterialTheme.typography.titleMedium)
                        state.peers.forEach { watch -> Choice(watch.name, state.settings.selectedNode == watch.id) { onSettings { it.copy(selectedNode = watch.id) } } }
                    } }
                    item { Text("Локально. Без аккаунтов, рекламы и аналитики.", style = MaterialTheme.typography.bodySmall) }
                }
                2 -> {
                    item { Panel {
                        Text("Состояние системы", style = MaterialTheme.typography.titleLarge)
                        Diagnostic("Watch connection", if (state.info.connected) "CONNECTED / NEARBY" else "DISCONNECTED")
                        Diagnostic("Wrist state", state.info.wrist.name)
                        Diagnostic("Sensor", state.info.sensor)
                        Diagnostic("Sensor available", if (state.info.sensorAvailable) "YES" else "NO")
                        Diagnostic("Last watch update", formatTime(state.info.lastUpdate))
                        Diagnostic("Last heartbeat", formatTime(state.info.lastHeartbeat))
                        Diagnostic("Phone ring volume", "${state.sound.volume} / $maximum")
                        Diagnostic("Ringer mode", modeTitle(state.sound.ringerMode))
                        Diagnostic("Automation", if (state.settings.enabled) state.info.phase.name else "DISABLED")
                        Diagnostic("Foreground service", if (state.info.running) "ACTIVE" else "STOPPED")
                        Diagnostic("DND access", if (state.policyAccess) "GRANTED" else "REQUIRED")
                        Diagnostic("System DND", if (state.dndActive) "ACTIVE" else "OFF")
                        TextButton(onClick = onRefresh) { Text("Обновить и запросить состояние") }
                    } }
                    item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("События · ${state.events.size}/100", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { onExport(state.events) }) { Text("Поделиться") }
                    } }
                    items(state.events.asReversed()) { event -> Panel {
                        Text(formatTime(event.time), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(event.message, style = MaterialTheme.typography.bodyMedium)
                    } }
                    if (state.events.isEmpty()) item { Text("Событий пока нет. Включите мониторинг на часах и автоматизацию на телефоне.") }
                    item { TextButton(onClick = onClear) { Text("Очистить журнал") } }
                }
                3 -> {
                    item { Panel {
                        Text("DX • Wrist Sound", style = MaterialTheme.typography.headlineSmall)
                        Text("Версия 1.2.4 · Автор dynorixz", style = MaterialTheme.typography.titleMedium)
                        Text("Телефон управляет звуком по датчику запястья часов. Часы предупреждают о подтверждённой потере телефона.")
                    } }
                    item { Panel {
                        links.forEach { link ->
                            Button(onClick = { onLink(link) }, modifier = Modifier.fillMaxWidth()) { Text(link.title) }
                            Text(link.url, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Ссылки открываются только по нажатию. Поддержка добровольна и не ограничивает функции приложения.", style = MaterialTheme.typography.bodySmall)
                    } }
                }
            }
        }
    }
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun InfoCard(title: String, body: String, action: @Composable () -> Unit) { Panel {
    Text(title, style = MaterialTheme.typography.titleMedium); Text(body, style = MaterialTheme.typography.bodyMedium); action()
} }
@Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = change)
    }
}
@Composable private fun Choice(title: String, selected: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = action); Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
@Composable private fun PercentSlider(value: Int, change: (Int) -> Unit) { IntegerSlider("Громкость", value, 0..100, "%", change) }
@Composable private fun IntegerSlider(title: String, value: Int, range: IntRange, unit: String, change: (Int) -> Unit) {
    var pending by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text("$title: ${pending.roundToInt()} $unit", style = MaterialTheme.typography.bodyMedium)
    Slider(value = pending, onValueChange = { pending = it }, onValueChangeFinished = { change(pending.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat())
}
@Composable private fun Rule(title: String, body: String) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(body, style = MaterialTheme.typography.bodyLarge)
} }
@Composable private fun Diagnostic(title: String, body: String) { Column {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(body, style = MaterialTheme.typography.bodyLarge)
} }
private fun wristTitle(state: WristState) = when (state) { WristState.ON_WRIST -> "Часы на руке"; WristState.OFF_WRIST -> "Часы сняты"; WristState.UNKNOWN -> "Состояние неизвестно" }
private fun modeTitle(mode: Int) = when (mode) { 0 -> "Без звука"; 1 -> "Вибрация"; else -> "Звук включён" }
private fun onRule(settings: AppSettings) = when (settings.onMode) { OnWristMode.SILENT -> "Телефон без звука"; OnWristMode.VIBRATE -> "Телефон вибрирует"; OnWristMode.CUSTOM -> "Громкость телефона ${settings.onPercent}%" }
private fun offRule(settings: AppSettings) = when (settings.offMode) { OffWristMode.MAXIMUM -> "Максимальная громкость"; OffWristMode.RESTORE -> "Восстановить предыдущую громкость"; OffWristMode.CUSTOM -> "Громкость телефона ${settings.offPercent}%" }
internal fun formatTime(time: Long): String = if (time == 0L) "Ещё не получено" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
