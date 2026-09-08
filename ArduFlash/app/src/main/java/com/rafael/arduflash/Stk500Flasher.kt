package com.rafael.arduflash

/**
 * Implementação mínima do protocolo STK500v1, suficiente pra gravar um
 * ATmega328P (Uno) via bootloader Optiboot. Baseado no mesmo handshake que
 * o avrdude usa quando você clica "Upload" na IDE do Arduino.
 *
 * Referência dos comandos: documentação Atmel AVR061 (STK500 protocol).
 */
class Stk500Flasher(private val usb: UsbSerialManager) {

    // Comandos do protocolo (subconjunto necessário)
    private val CMD_GET_SYNC = 0x30
    private val CMD_ENTER_PROGMODE = 0x50
    private val CMD_LEAVE_PROGMODE = 0x51
    private val CMD_LOAD_ADDRESS = 0x55
    private val CMD_PROGRAM_PAGE = 0x64
    private val SYNC_CRC_EOP = 0x20

    private val RESP_INSYNC = 0x14
    private val RESP_OK = 0x10

    companion object {
        private const val PAGE_SIZE = 128 // tamanho de página de flash do ATmega328P
    }

    var onProgress: ((current: Int, total: Int) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    /**
     * Executa a gravação completa: reset via DTR, handshake, escrita de todas
     * as páginas e saída do modo de programação.
     * Lança exceção se qualquer etapa falhar (sync, page write, etc).
     */
    fun flash(hexText: String) {
        val records = IntelHexParser.parse(hexText)
        val image = IntelHexParser.toFlashImage(records, PAGE_SIZE)

        onLog?.invoke("Resetando placa via DTR...")
        usb.pulseResetViaDtr()

        onLog?.invoke("Sincronizando com o bootloader...")
        syncWithRetries()

        onLog?.invoke("Entrando em modo de programação...")
        sendCommand(byteArrayOf(CMD_ENTER_PROGMODE.toByte()))

        val totalPages = image.size / PAGE_SIZE
        for (page in 0 until totalPages) {
            val address = page * PAGE_SIZE
            val pageData = image.copyOfRange(address, address + PAGE_SIZE)

            loadAddress(address / 2) // STK500 usa endereço em WORDS, não bytes
            programPage(pageData)

            onProgress?.invoke(page + 1, totalPages)
        }

        onLog?.invoke("Saindo do modo de programação...")
        sendCommand(byteArrayOf(CMD_LEAVE_PROGMODE.toByte()))
        onLog?.invoke("Gravação concluída com sucesso.")
    }

    private fun syncWithRetries(maxAttempts: Int = 10) {
        repeat(maxAttempts) { attempt ->
            usb.write(byteArrayOf(CMD_GET_SYNC.toByte(), SYNC_CRC_EOP.toByte()))
            val response = usb.readBlocking(2, timeoutMs = 300)
            if (response.size == 2 &&
                response[0].toInt() and 0xFF == RESP_INSYNC &&
                response[1].toInt() and 0xFF == RESP_OK
            ) {
                onLog?.invoke("Sincronizado (tentativa ${attempt + 1}).")
                return
            }
        }
        throw IllegalStateException("Não foi possível sincronizar com o bootloader. Confira baud rate e fiação.")
    }

    private fun loadAddress(wordAddress: Int) {
        val lo = wordAddress and 0xFF
        val hi = (wordAddress shr 8) and 0xFF
        sendCommand(byteArrayOf(CMD_LOAD_ADDRESS.toByte(), lo.toByte(), hi.toByte()))
    }

    private fun programPage(pageData: ByteArray) {
        val sizeHi = (pageData.size shr 8) and 0xFF
        val sizeLo = pageData.size and 0xFF
        val payload = byteArrayOf(
            CMD_PROGRAM_PAGE.toByte(),
            sizeHi.toByte(),
            sizeLo.toByte(),
            'F'.code.toByte() // 'F' = memória flash (vs 'E' = EEPROM)
        ) + pageData
        sendCommand(payload)
    }

    /** Envia um comando finalizado com SYNC_CRC_EOP e valida resposta INSYNC/OK. */
    private fun sendCommand(commandBytes: ByteArray) {
        usb.write(commandBytes + SYNC_CRC_EOP.toByte())
        val response = usb.readBlocking(2, timeoutMs = 1000)
        val ok = response.size == 2 &&
                response[0].toInt() and 0xFF == RESP_INSYNC &&
                response[1].toInt() and 0xFF == RESP_OK
        if (!ok) {
            throw IllegalStateException("Placa não respondeu OK ao comando 0x${commandBytes[0].toString(16)}")
        }
    }
}
