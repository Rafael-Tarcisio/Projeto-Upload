package com.rafael.hexuploader

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ArrayBlockingQueue

class MainActivity : AppCompatActivity() {

    private lateinit var txtConnectionStatus: TextView
    private lateinit var btnPickFile: Button
    private lateinit var btnUpload: Button
    private lateinit var txtFileName: TextView
    private lateinit var txtStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var spinnerBaudRate: Spinner
    private lateinit var spinnerMonitorBaud: Spinner
    private lateinit var spinnerTerminator: Spinner
    private lateinit var btnToggleMonitor: Button
    private lateinit var btnClearMonitor: Button
    private lateinit var txtSerialOutput: TextView
    private lateinit var scrollMonitor: ScrollView
    private lateinit var editSendData: EditText
    private lateinit var btnSend: Button

    private lateinit var prefs: SharedPreferences

    private var selectedHexText: String? = null
    private var monitor: SerialMonitor? = null
    private var monitorPort: UsbSerialPort? = null
    private val ACTION_USB_PERMISSION = "com.rafael.hexuploader.USB_PERMISSION"
    private val ACTION_USB_PERMISSION_MONITOR = "com.rafael.hexuploader.USB_PERMISSION_MONITOR"

    // Assinaturas de chip mais comuns em placas Arduino (usadas só para
    // mostrar o nome do chip detectado antes de confirmar a gravação)
    private val KNOWN_SIGNATURES = mapOf(
        listOf(0x1E, 0x95, 0x0F) to "ATmega328P (Uno / Nano / Pro Mini)",
        listOf(0x1E, 0x94, 0x06) to "ATmega168",
        listOf(0x1E, 0x95, 0x87) to "ATmega32U4 (Leonardo / Micro)",
        listOf(0x1E, 0x98, 0x01) to "ATmega2560 (Mega)"
    )

    private val filePickerLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { loadHexFile(it) }
        }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (granted) doUpload() else setStatus("Permissão de acesso à USB negada.")
                }
                ACTION_USB_PERMISSION_MONITOR -> {
                    if (granted) openMonitor() else setStatus("Permissão de acesso à USB negada.")
                }
            }
        }
    }

    // Detecta conexão/desconexão do Arduino em tempo real, sem precisar
    // apertar nenhum botão — atualiza o indicador no topo da tela.
    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshConnectionStatus()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    closeMonitor()
                    refreshConnectionStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("hexuploader_prefs", Context.MODE_PRIVATE)

        txtConnectionStatus = findViewById(R.id.txtConnectionStatus)
        btnPickFile = findViewById(R.id.btnPickFile)
        btnUpload = findViewById(R.id.btnUpload)
        txtFileName = findViewById(R.id.txtFileName)
        txtStatus = findViewById(R.id.txtStatus)
        progressBar = findViewById(R.id.progressBar)
        spinnerBaudRate = findViewById(R.id.spinnerBaudRate)
        spinnerMonitorBaud = findViewById(R.id.spinnerMonitorBaud)
        spinnerTerminator = findViewById(R.id.spinnerTerminator)
        btnToggleMonitor = findViewById(R.id.btnToggleMonitor)
        btnClearMonitor = findViewById(R.id.btnClearMonitor)
        txtSerialOutput = findViewById(R.id.txtSerialOutput)
        scrollMonitor = findViewById(R.id.scrollMonitor)
        editSendData = findViewById(R.id.editSendData)
        btnSend = findViewById(R.id.btnSend)

        setupSpinner(spinnerBaudRate, R.array.baud_rates, "flash_baud_index", 0)
        setupSpinner(spinnerMonitorBaud, R.array.baud_rates, "monitor_baud_index", 4)
        setupSpinner(spinnerTerminator, R.array.line_terminators, "terminator_index", 1)

        val permissionFilter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(ACTION_USB_PERMISSION_MONITOR)
        }
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbAttachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, permissionFilter)
            registerReceiver(usbAttachReceiver, attachFilter)
        }

        btnPickFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        btnUpload.setOnClickListener {
            requestUsbPermissionAndUpload()
        }

        btnToggleMonitor.setOnClickListener {
            if (monitor == null) {
                requestUsbPermissionAndOpenMonitor()
            } else {
                closeMonitor()
            }
        }

        btnClearMonitor.setOnClickListener {
            txtSerialOutput.text = ""
        }

        btnSend.setOnClickListener {
            val text = editSendData.text.toString()
            if (text.isNotEmpty()) {
                try {
                    monitor?.send(text + currentTerminator())
                    editSendData.text.clear()
                } catch (e: Exception) {
                    setStatus("Erro ao enviar: ${e.message}")
                }
            }
        }

        refreshConnectionStatus()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        closeMonitor()
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbAttachReceiver)
    }

    /** Cria o spinner, restaura a última opção usada e salva a escolha a cada mudança. */
    private fun setupSpinner(spinner: Spinner, arrayRes: Int, prefKey: String, defaultIndex: Int) {
        ArrayAdapter.createFromResource(this, arrayRes, android.R.layout.simple_spinner_item).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            spinner.setSelection(prefs.getInt(prefKey, defaultIndex))
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(prefKey, position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun currentTerminator(): String = when (spinnerTerminator.selectedItemPosition) {
        1 -> "\n"
        2 -> "\r\n"
        else -> ""
    }

    /** Trata o app sendo aberto com um .hex vindo de outro app ("Abrir com" / "Compartilhar"). */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let { loadHexFile(it) }
    }

    private fun refreshConnectionStatus() {
        val driver = findDriver()
        txtConnectionStatus.text = if (driver != null) {
            "🟢 Adaptador USB-serial conectado"
        } else {
            "🔴 Nenhum dispositivo conectado"
        }
    }

    private fun loadHexFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val text = BufferedReader(InputStreamReader(stream)).readText()
                selectedHexText = text
                txtFileName.text = uri.lastPathSegment ?: "arquivo selecionado"
                btnUpload.isEnabled = true
                setStatus("Arquivo carregado. Pronto para gravar.")
            }
        } catch (e: Exception) {
            setStatus("Erro ao ler o arquivo: ${e.message}")
        }
    }

    private fun findDriver(): UsbSerialDriver? {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        return drivers.firstOrNull()
    }

    private fun requestUsbPermissionAndUpload() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = findDriver()
        if (driver == null) {
            setStatus("Nenhum adaptador USB-serial encontrado. Verifique o cabo OTG.")
            return
        }
        val device = driver.device
        if (manager.hasPermission(device)) {
            doUpload()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            manager.requestPermission(device, permissionIntent)
        }
    }

    private fun doUpload() {
        val hexText = selectedHexText
        if (hexText == null) {
            setStatus("Selecione um arquivo .hex primeiro.")
            return
        }

        closeMonitor() // libera a porta serial antes de gravar

        val baudRate = (spinnerBaudRate.selectedItem as String).toInt()
        btnUpload.isEnabled = false
        progressBar.progress = 0
        setStatus("Analisando arquivo .hex...")

        Thread {
            var port: UsbSerialPort? = null
            try {
                val memory = IntelHexParser.parse(hexText)

                val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                val driver = findDriver()
                    ?: throw IllegalStateException("Adaptador USB desconectado.")
                val connection = manager.openDevice(driver.device)
                    ?: throw IllegalStateException("Não foi possível abrir a conexão USB (falta permissão?).")

                port = driver.ports[0]
                port.open(connection)
                port.setParameters(
                    baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE
                )

                // Auto-reset: a maioria das placas Arduino (com bootloader Optiboot)
                // reinicia quando o DTR cai. Isso reproduz o comportamento do avrdude.
                port.dtr = false
                Thread.sleep(100)
                port.dtr = true
                Thread.sleep(50)
                port.dtr = false
                Thread.sleep(250) // tempo para o bootloader iniciar e esperar sync

                runOnUiThread { setStatus("Sincronizando com o bootloader...") }
                val stk = Stk500v1(port)
                stk.sync()

                runOnUiThread { setStatus("Lendo assinatura do chip...") }
                val sig = stk.readSignature()
                val sigKey = listOf(sig[0].toInt() and 0xFF, sig[1].toInt() and 0xFF, sig[2].toInt() and 0xFF)
                val chipName = KNOWN_SIGNATURES[sigKey]
                    ?: "desconhecido (${sig.joinToString(" ") { "%02X".format(it) }})"

                val confirmQueue = ArrayBlockingQueue<Boolean>(1)
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Chip detectado")
                        .setMessage("$chipName\n\nContinuar com a gravação?")
                        .setCancelable(false)
                        .setPositiveButton("Gravar") { _, _ -> confirmQueue.put(true) }
                        .setNegativeButton("Cancelar") { _, _ -> confirmQueue.put(false) }
                        .show()
                }
                val confirmed = confirmQueue.take()
                if (!confirmed) {
                    port.close()
                    runOnUiThread {
                        setStatus("Gravação cancelada.")
                        btnUpload.isEnabled = true
                    }
                    return@Thread
                }

                runOnUiThread { setStatus("Entrando em modo de programação...") }
                stk.enterProgMode()

                stk.writeFlash(memory) { page, total ->
                    runOnUiThread {
                        progressBar.progress = (page * 100) / total
                        setStatus("Gravando página $page de $total...")
                    }
                }

                stk.leaveProgMode()
                port.close()

                runOnUiThread {
                    setStatus("Gravação concluída com sucesso!")
                    btnUpload.isEnabled = true
                }
            } catch (e: Exception) {
                try { port?.close() } catch (_: Exception) {}
                runOnUiThread {
                    setStatus("Erro: ${e.message}")
                    btnUpload.isEnabled = true
                }
            }
        }.start()
    }

    private fun setStatus(text: String) {
        runOnUiThread { txtStatus.text = text }
    }

    private fun requestUsbPermissionAndOpenMonitor() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = findDriver()
        if (driver == null) {
            setStatus("Nenhum adaptador USB-serial encontrado. Verifique o cabo OTG.")
            return
        }
        val device = driver.device
        if (manager.hasPermission(device)) {
            openMonitor()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this, 1, Intent(ACTION_USB_PERMISSION_MONITOR), flags
            )
            manager.requestPermission(device, permissionIntent)
        }
    }

    /**
     * Abre a porta serial e começa a escutar. Se o botão "Gravar" também
     * estiver ativo, note que só uma coisa pode ter a porta aberta por vez —
     * feche o monitor antes de gravar de novo, e vice-versa.
     */
    private fun openMonitor() {
        try {
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = findDriver()
                ?: throw IllegalStateException("Adaptador USB desconectado.")
            val connection = manager.openDevice(driver.device)
                ?: throw IllegalStateException("Não foi possível abrir a conexão USB.")

            val baudRate = (spinnerMonitorBaud.selectedItem as String).toInt()
            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            monitorPort = port

            monitor = SerialMonitor(
                port = port,
                onData = { text -> appendSerialOutput(text) },
                onError = { e ->
                    runOnUiThread {
                        setStatus("Monitor: conexão perdida (${e.message})")
                        closeMonitor()
                    }
                }
            ).also { it.start() }

            btnToggleMonitor.text = "Fechar Monitor Serial"
            setStatus("Monitor serial aberto em $baudRate baud.")
        } catch (e: Exception) {
            setStatus("Erro ao abrir monitor: ${e.message}")
            closeMonitor()
        }
    }

    private fun closeMonitor() {
        monitor?.stop()
        monitor = null
        try {
            monitorPort?.close()
        } catch (e: Exception) {
            // porta já pode estar fechada/desconectada, ignora
        }
        monitorPort = null
        btnToggleMonitor.text = "Abrir Monitor Serial"
    }

    private fun appendSerialOutput(text: String) {
        runOnUiThread {
            txtSerialOutput.append(text)
            scrollMonitor.post { scrollMonitor.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
