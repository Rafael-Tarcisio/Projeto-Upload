package com.rafael.hexuploader

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * Implementação mínima do protocolo STK500v1, usado pelo bootloader
 * padrão do Arduino (Optiboot / stk500boot) para receber o firmware.
 *
 * Referência dos bytes de comando: mesma tabela usada pelo avrdude
 * (arquivo stk500.c), reduzida ao subconjunto necessário para gravar
 * a flash de um ATmega328P/32u4.
 */
class Stk500v1(private val port: UsbSerialPort, private val pageSize: Int = 128) {

    companion object {
        private const val STK_OK = 0x10
        private const val STK_INSYNC = 0x14
        private const val CRC_EOP = 0x20
        private const val STK_GET_SYNC = 0x30
        private const val STK_ENTER_PROGMODE = 0x50
        private const val STK_LEAVE_PROGMODE = 0x51
        private const val STK_LOAD_ADDRESS = 0x55
        private const val STK_PROG_PAGE = 0x64
        private const val STK_READ_SIGN = 0x75

        private const val READ_TIMEOUT_MS = 1000
    }

    class ProtocolException(message: String) : Exception(message)

    /** Lê os 3 bytes de assinatura do chip (ex: 1E 95 0F = ATmega328P). */
    fun readSignature(): ByteArray {
        send(byteArrayOf(STK_READ_SIGN.toByte(), CRC_EOP.toByte()))
        val resp = readExact(5)
        val inSync = resp[0].toInt() and 0xFF
        val ok = resp[4].toInt() and 0xFF
        if (inSync != STK_INSYNC || ok != STK_OK) {
            throw ProtocolException("Resposta inesperada ao ler a assinatura do chip")
        }
        return byteArrayOf(resp[1], resp[2], resp[3])
    }

    /** Sincroniza com o bootloader. Deve ser chamado logo após o reset (toggle de DTR). */
    fun sync(maxAttempts: Int = 15) {
        repeat(maxAttempts) {
            try {
                send(byteArrayOf(STK_GET_SYNC.toByte(), CRC_EOP.toByte()))
                val resp = readExact(2)
                if (resp[0].toInt() and 0xFF == STK_INSYNC && resp[1].toInt() and 0xFF == STK_OK) {
                    return
                }
            } catch (e: IOException) {
                // ignora e tenta de novo
            }
        }
        throw ProtocolException("Não foi possível sincronizar com o bootloader (sem resposta STK_INSYNC/STK_OK)")
    }

    fun enterProgMode() {
        send(byteArrayOf(STK_ENTER_PROGMODE.toByte(), CRC_EOP.toByte()))
        expectOk()
    }

    fun leaveProgMode() {
        send(byteArrayOf(STK_LEAVE_PROGMODE.toByte(), CRC_EOP.toByte()))
        expectOk()
    }

    /** Grava toda a memória (já linearizada pelo IntelHexParser), página por página. */
    fun writeFlash(memory: ByteArray, onProgress: (Int, Int) -> Unit) {
        var address = 0 // endereço em WORDS (2 bytes), como o protocolo espera
        var offset = 0
        val totalPages = (memory.size + pageSize - 1) / pageSize
        var pageIndex = 0

        while (offset < memory.size) {
            val chunkSize = minOf(pageSize, memory.size - offset)
            val page = memory.copyOfRange(offset, offset + chunkSize)

            loadAddress(address)
            progPage(page)

            offset += chunkSize
            address += chunkSize / 2
            pageIndex++
            onProgress(pageIndex, totalPages)
        }
    }

    private fun loadAddress(wordAddress: Int) {
        val low = wordAddress and 0xFF
        val high = (wordAddress shr 8) and 0xFF
        send(byteArrayOf(STK_LOAD_ADDRESS.toByte(), low.toByte(), high.toByte(), CRC_EOP.toByte()))
        expectOk()
    }

    private fun progPage(data: ByteArray) {
        val lenHigh = (data.size shr 8) and 0xFF
        val lenLow = data.size and 0xFF
        val header = byteArrayOf(
            STK_PROG_PAGE.toByte(), lenHigh.toByte(), lenLow.toByte(), 'F'.code.toByte()
        )
        send(header + data + byteArrayOf(CRC_EOP.toByte()))
        expectOk()
    }

    private fun expectOk() {
        val resp = readExact(2)
        val inSync = resp[0].toInt() and 0xFF
        val ok = resp[1].toInt() and 0xFF
        if (inSync != STK_INSYNC || ok != STK_OK) {
            throw ProtocolException("Resposta inesperada do bootloader: ${resp.joinToString { "%02X".format(it) }}")
        }
    }

    private fun send(bytes: ByteArray) {
        port.write(bytes, READ_TIMEOUT_MS)
    }

    private fun readExact(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var read = 0
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        while (read < n) {
            val chunk = ByteArray(n - read)
            val got = port.read(chunk, 200)
            if (got > 0) {
                System.arraycopy(chunk, 0, buffer, read, got)
                read += got
            }
            if (read < n && System.currentTimeMillis() > deadline) {
                throw IOException("Timeout esperando resposta do bootloader ($read/$n bytes)")
            }
        }
        return buffer
    }
}
