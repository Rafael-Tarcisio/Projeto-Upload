package com.rafael.hexuploader

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

/**
 * Fica lendo a porta serial em segundo plano e repassa cada trecho de
 * dados recebido via [onData]. Equivalente ao que o Monitor Serial da
 * Arduino IDE faz — aqui sem parsear nada, só entrega os bytes crus
 * decodificados como texto (o chamador decide o que fazer com eles).
 */
class SerialMonitor(
    private val port: UsbSerialPort,
    private val onData: (String) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var ioManager: SerialInputOutputManager? = null

    fun start() {
        val listener = object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                onData(String(data, Charsets.UTF_8))
            }

            override fun onRunError(e: Exception) {
                onError(e)
            }
        }
        val manager = SerialInputOutputManager(port, listener)
        ioManager = manager
        executor.submit(manager)
    }

    fun stop() {
        ioManager?.stop()
        ioManager = null
    }

    fun send(text: String) {
        port.write(text.toByteArray(Charsets.UTF_8), 500)
    }
}
