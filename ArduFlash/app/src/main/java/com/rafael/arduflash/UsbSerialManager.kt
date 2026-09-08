package com.rafael.arduflash

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

/**
 * Encapsula a conexão OTG com a placa. Serve tanto pro fluxo de gravação
 * (Stk500Flasher) quanto pro monitor serial (leitura contínua livre).
 */
class UsbSerialManager(private val context: Context) {

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    var onDataReceived: ((ByteArray) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    /** Baud rate padrão do bootloader Optiboot no Uno R3. Ajustável na UI se necessário. */
    var baudRate: Int = 115200

    fun findAvailableDriver(): UsbSerialDriver? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        return drivers.firstOrNull()
    }

    /** Pede permissão ao usuário (o Android exige confirmação explícita pra acesso USB). */
    fun requestPermission(driver: UsbSerialDriver, onGranted: () -> Unit, onDenied: () -> Unit) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(driver.device)) {
            onGranted()
            return
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_MUTABLE
        )
        // Registro do BroadcastReceiver correspondente deve ficar na Activity (ver MainActivity)
        manager.requestPermission(driver.device, permissionIntent)
        // onGranted/onDenied são chamados pela Activity ao receber o broadcast
    }

    fun connect(driver: UsbSerialDriver): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = manager.openDevice(driver.device) ?: return false

        val serialPort = driver.ports[0]
        serialPort.open(connection)
        serialPort.setParameters(
            baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE
        )
        port = serialPort

        val listener = object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = onDataReceived?.invoke(data) ?: Unit
            override fun onRunError(e: Exception) { onError?.invoke(e) }
        }
        ioManager = SerialInputOutputManager(serialPort, listener).also { executor.submit(it) }
        return true
    }

    /**
     * Pulsa o pino DTR — é isso que faz o bootloader Optiboot "acordar" e
     * esperar dados de gravação por ~1 segundo (mesmo truque que a IDE do
     * Arduino faz sozinha ao clicar em Upload).
     */
    fun pulseResetViaDtr() {
        port?.let {
            it.dtr = false
            Thread.sleep(50)
            it.dtr = true
            Thread.sleep(50)
            it.dtr = false
            Thread.sleep(50) // dá tempo do bootloader inicializar antes de mandar dados
        }
    }

    fun write(data: ByteArray) {
        port?.write(data, WRITE_TIMEOUT_MS)
    }

    /** Leitura bloqueante simples, usada pelo protocolo STK500 (que é request/response). */
    fun readBlocking(maxBytes: Int, timeoutMs: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        val read = port?.read(buffer, timeoutMs) ?: 0
        return buffer.copyOf(read)
    }

    fun disconnect() {
        ioManager?.stop()
        port?.close()
        port = null
        ioManager = null
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.rafael.arduflash.USB_PERMISSION"
        private const val WRITE_TIMEOUT_MS = 2000
    }
}
