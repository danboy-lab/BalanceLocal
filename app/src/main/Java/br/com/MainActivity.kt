package br.com.balancelocal

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

private const val TARGET_MAC = "78:66:A5:5C:22:91"

data class ScaleReading(
    val weightKg: Double?,
    val impedanceOhms: Int?,
    val rollingCounter: Int,
    val unitStatus: Int,
    val weighingState: Int,
    val checksum: Int,
    val rawHex: String,
    val mac: String
)

object ChipseaParser {
    /*
     * Expected 17-byte layout:
     * 0 length, 1 data type, 2 subtype, 3 counter,
     * 4-5 weight, 6-7 impedance, 8 status, 9 state,
     * 10 checksum, 11-16 embedded MAC.
     *
     * The scale/indices are intentionally centralized because
     * the first sample supplied by the user has a weight mismatch.
     */
    var weightStart = 4
    var weightScale = 100.0
    var weightBigEndian = true

    fun parse(bytes: ByteArray, deviceMac: String): ScaleReading? {
        if (bytes.size < 17) return null
        val b = bytes.map { it.toInt() and 0xff }
        if (b[2] != 0xC0) return null

        val rawWeight = if (weightBigEndian) {
            (b[weightStart] shl 8) or b[weightStart + 1]
        } else {
            b[weightStart] or (b[weightStart + 1] shl 8)
        }

        val impedance = (b[6] shl 8) or b[7]
        val embeddedMac = b.subList(11, 17)
            .joinToString(":") { "%02X".format(it) }

        return ScaleReading(
            weightKg = rawWeight / weightScale,
            impedanceOhms = impedance,
            rollingCounter = b[3],
            unitStatus = b[8],
            weighingState = b[9],
            checksum = b[10],
            rawHex = b.joinToString(" ") { "%02X".format(it) },
            mac = if (deviceMac.isNotBlank()) deviceMac else embeddedMac
        )
    }
}

class BleScaleScanner(private val context: Context) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    var onReading: ((ScaleReading) -> Unit)? = null
    var onDeviceFound: ((String, String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            onError?.invoke("Bluetooth está desligado ou indisponível.")
            return
        }
        scanner = adapter.bluetoothLeScanner
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = device.address ?: return
                val name = device.name ?: "Balança BLE"
                onDeviceFound?.invoke(name, address)

                val record = result.scanRecord ?: return
                val manufacturerData = record.manufacturerSpecificData
                for (i in 0 until manufacturerData.size()) {
                    val payload = manufacturerData.valueAt(i) ?: continue
                    // Some Android APIs return manufacturer data without FF.
                    val candidates = listOf(payload, byteArrayOf(0xFF.toByte()) + payload)
                    for (candidate in candidates) {
                        val parsed = ChipseaParser.parse(candidate, address)
                        if (parsed != null) {
                            onReading?.invoke(parsed)
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                onError?.invoke("Falha no scan BLE: $errorCode")
            }
        }
        scanner?.startScan(callback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        callback?.let { scanner?.stopScan(it) }
        callback = null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BalanceLocalApp(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceLocalApp(context: Context) {
    var tab by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var lastReading by remember { mutableStateOf<ScaleReading?>(null) }
    var devices by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var error by remember { mutableStateOf<String?>(null) }

    val scanner = remember { BleScaleScanner(context) }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            scanner.start()
            scanning = true
        } else {
            error = "Permissões Bluetooth recusadas."
        }
    }

    DisposableEffect(Unit) {
        scanner.onReading = { reading ->
            lastReading = reading
        }
        scanner.onDeviceFound = { name, address ->
            if (devices.none { it.second == address }) {
                devices = devices + (name to address)
            }
        }
        scanner.onError = { error = it }
        onDispose { scanner.stop() }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF4F46E5),
            background = androidx.compose.ui.graphics.Color(0xFFF8F8FC)
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Balance Local") },
                    actions = {
                        IconButton(onClick = {
                            if (scanning) {
                                scanner.stop()
                                scanning = false
                            } else {
                                val needed = if (Build.VERSION.SDK_INT >= 31) {
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    )
                                } else {
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                                val allGranted = needed.all {
                                    ContextCompat.checkSelfPermission(context, it) ==
                                        PackageManager.PERMISSION_GRANTED
                                }
                                if (allGranted) {
                                    scanner.start()
                                    scanning = true
                                } else {
                                    permissions.launch(needed)
                                }
                            }
                        }) {
                            Icon(
                                if (scanning) Icons.Default.Stop else Icons.Default.Bluetooth,
                                contentDescription = "Bluetooth"
                            )
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Home, null) },
                        label = { Text("Início") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Bluetooth, null) },
                        label = { Text("Conectar") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.BugReport, null) },
                        label = { Text("Diagnóstico") }
                    )
                }
            }
        ) { padding ->
            when (tab) {
                0 -> HomeScreen(lastReading, padding)
                1 -> ConnectScreen(
                    devices = devices,
                    scanning = scanning,
                    lastReading = lastReading,
                    padding = padding
                )
                2 -> DiagnosticScreen(lastReading, error, padding)
            }
        }
    }
}

@Composable
fun HomeScreen(reading: ScaleReading?, padding: PaddingValues) {
    val weight = reading?.weightKg
    val heightM = 1.82
    val bmi = weight?.let { it / (heightM * heightM) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Resumo", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Dados locais, sem anúncios e sem conta obrigatória.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            MetricCard(
                title = "Peso atual",
                value = weight?.let { "%.2f kg".format(Locale.US, it) } ?: "—",
                subtitle = if (reading == null) "Conecte a balança para ler" else "Último pacote recebido"
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallMetric("IMC", bmi?.let { "%.1f".format(Locale.US, it) } ?: "—", Modifier.weight(1f))
                SmallMetric("TMB", weight?.let { "%.0f kcal".format(Locale.US, 10 * it + 6.25 * 182 - 5 * 23 + 5) } ?: "—", Modifier.weight(1f))
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Composição corporal", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Gordura corporal: indisponível")
                    Text("Massa muscular: indisponível")
                    Text("Água corporal: indisponível")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Esses valores só serão mostrados quando o protocolo fornecer dados confiáveis. O app não inventa estimativas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun MetricCard(title: String, value: String, subtitle: String) {
    Card {
        Column(Modifier.padding(20.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.displaySmall)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SmallMetric(title: String, value: String, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun ConnectScreen(
    devices: List<Pair<String, String>>,
    scanning: Boolean,
    lastReading: ScaleReading?,
    padding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Conectar balança", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (scanning) "Procurando anúncios BLE..." else "Toque no Bluetooth no topo para iniciar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Dispositivo esperado", style = MaterialTheme.typography.titleMedium)
                    Text("Chipsea / Top Top")
                    Text(TARGET_MAC)
                    Spacer(Modifier.height(8.dp))
                    Text("O app procura Manufacturer Data compatível com o subtipo 0xC0.")
                }
            }
        }
        item {
            Text("Dispositivos encontrados", style = MaterialTheme.typography.titleMedium)
        }
        if (devices.isEmpty()) {
            item { Text("Nenhum dispositivo encontrado ainda.") }
        } else {
            items(devices) { device ->
                ListItem(
                    headlineContent = { Text(device.first) },
                    supportingContent = { Text(device.second) },
                    leadingContent = { Icon(Icons.Default.Bluetooth, null) }
                )
            }
        }
        lastReading?.let {
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Última leitura recebida", style = MaterialTheme.typography.titleMedium)
                        Text("Peso: ${it.weightKg?.let { w -> "%.2f kg".format(Locale.US, w) }}")
                        Text("Impedância: ${it.impedanceOhms} Ω")
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticScreen(
    reading: ScaleReading?,
    error: String?,
    padding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Diagnóstico", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Use esta tela para validar os bytes antes de confiar nos cálculos.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        error?.let {
            item {
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )) {
                    Text(it, Modifier.padding(16.dp))
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Protocolo", style = MaterialTheme.typography.titleMedium)
                    Text("Chipsea BLE / Manufacturer Data")
                    Text("Subtipo esperado: 0xC0")
                    Text("MAC alvo: $TARGET_MAC")
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Pacote bruto", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(reading?.rawHex ?: "Nenhum pacote recebido.")
                }
            }
        }
        reading?.let {
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Campos interpretados", style = MaterialTheme.typography.titleMedium)
                        Text("Contador: 0x${it.rollingCounter.toString(16).uppercase()}")
                        Text("Peso interpretado: ${"%.2f".format(Locale.US, it.weightKg)} kg")
                        Text("Impedância: ${it.impedanceOhms} Ω")
                        Text("Unidade/status: 0x${it.unitStatus.toString(16).uppercase()}")
                        Text("Estado: 0x${it.weighingState.toString(16).uppercase()}")
                        Text("Checksum: 0x${it.checksum.toString(16).uppercase()}")
                        Text("MAC: ${it.mac}")
                    }
                }
            }
        }
    }
}
