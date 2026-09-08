package com.rafael.arduflash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.rafael.arduflash.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbSerialManager
    private var pickedHexText: String? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadHexFile(it) }
    }

    // Recebe a resposta do diálogo de permissão USB que o Android mostra pro usuário
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbSerialManager.ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) doConnect() else log("Permissão USB negada.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = UsbSerialManager(this)

        val filter = IntentFilter(UsbSerialManager.ACTION_USB_PERMISSION)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(usbPermissionReceiver, filter, flags)

        binding.btnConnect.setOnClickListener { requestConnection() }
        binding.btnPickFile.setOnClickListener { filePicker.launch("*/*") }
        binding.btnFlash.setOnClickListener { startFlashing() }
        binding.btnSend.setOnClickListener { sendManualSerial() }

        usbManager.onDataReceived = { data -> runOnUiThread { appendSerialOutput(data) } }
        usbManager.onError = { e -> runOnUiThread { log("Erro na serial: ${e.message}") } }
    }

    private fun requestConnection() {
        val driver = usbManager.findAvailableDriver()
        if (driver == null) {
            log("Nenhuma placa encontrada via OTG. Confira o cabo/adaptador.")
            return
        }
        usbManager.requestPermission(
            driver,
            onGranted = { doConnect() },
            onDenied = { log("Permissão negada pelo usuário.") }
        )
    }

    private fun doConnect() {
        val driver = usbManager.findAvailableDriver() ?: return
        val connected = usbManager.connect(driver)
        binding.statusText.text = if (connected) "Placa: conectada" else "Placa: falha ao conectar"
        log(if (connected) "Conectado com sucesso." else "Falha ao abrir a conexão USB.")
    }

    private fun loadHexFile(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { stream ->
            pickedHexText = stream.bufferedReader().readText()
            log("Arquivo .hex carregado (${pickedHexText?.length} caracteres).")
        }
    }

    private fun startFlashing() {
        val hexText = pickedHexText
        if (hexText == null) {
            Toast.makeText(this, "Escolha um arquivo .hex primeiro", Toast.LENGTH_SHORT).show()
            return
        }

        val flasher = Stk500Flasher(usbManager)
        flasher.onLog = { msg -> runOnUiThread { log(msg) } }
        flasher.onProgress = { current, total ->
            runOnUiThread { binding.progressBar.progress = (current * 100) / total }
        }

        // Gravação é bloqueante (protocolo request/response), então roda fora da UI thread
        thread {
            try {
                flasher.flash(hexText)
            } catch (e: Exception) {
                runOnUiThread { log("ERRO na gravação: ${e.message}") }
            }
        }
    }

    private fun sendManualSerial() {
        val text = binding.inputSerial.text.toString()
        if (text.isNotEmpty()) {
            usbManager.write((text + "\n").toByteArray())
            binding.inputSerial.text.clear()
        }
    }

    private fun appendSerialOutput(data: ByteArray) {
        binding.logText.append(String(data))
    }

    private fun log(message: String) {
        binding.logText.append("\n$message")
    }

    override fun onDestroy() {
        super.onDestroy()
        usbManager.disconnect()
        unregisterReceiver(usbPermissionReceiver)
    }
}
