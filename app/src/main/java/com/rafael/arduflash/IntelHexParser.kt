package com.rafael.arduflash

/**
 * Representa um trecho contíguo de bytes já decodificado, pronto pra ser
 * gravado a partir de [address].
 */
data class HexRecord(val address: Int, val data: ByteArray)

/**
 * Parser simples de Intel HEX (formato padrão de saída do avr-gcc/Arduino IDE).
 *
 * Cada linha segue o formato:
 *  `:LLAAAATT[DD...]CC`
 *   LL   = tamanho dos dados (1 byte, hex)
 *   AAAA = endereço (2 bytes, hex)
 *   TT   = tipo do registro (00=dados, 01=EOF, 04=endereço estendido)
 *   DD   = bytes de dados (LL bytes)
 *   CC   = checksum (complemento de 2 da soma de todos os bytes acima)
 */
object IntelHexParser {

    fun parse(hexText: String): List<HexRecord> {
        val records = mutableListOf<HexRecord>()
        var upperAddress = 0 // usado só se o .hex tiver endereços > 64KB (tipo 04)

        hexText.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(":") }
            .forEach { line ->
                val bytes = hexLineToBytes(line)
                val length = bytes[0].toInt() and 0xFF
                val addrHi = bytes[1].toInt() and 0xFF
                val addrLo = bytes[2].toInt() and 0xFF
                val recordType = bytes[3].toInt() and 0xFF
                val data = bytes.copyOfRange(4, 4 + length)

                // (checksum já foi validado dentro de hexLineToBytes)

                when (recordType) {
                    0x00 -> { // dados
                        val fullAddress = (upperAddress shl 16) or (addrHi shl 8) or addrLo
                        records.add(HexRecord(fullAddress, data))
                    }
                    0x01 -> { /* fim de arquivo, nada a fazer */ }
                    0x04 -> { // endereço estendido (parte alta de 32 bits)
                        upperAddress = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    }
                    // outros tipos (02, 03, 05) raramente aparecem em .hex de AVR — ignorados
                }
            }

        return records
    }

    /** Converte uma linha ":10...CC" nos bytes crus, validando o checksum. */
    private fun hexLineToBytes(line: String): ByteArray {
        val hex = line.substring(1) // remove o ':'
        require(hex.length % 2 == 0) { "Linha .hex com tamanho inválido: $line" }

        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        val sum = bytes.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } and 0xFF
        require(sum == 0) { "Checksum inválido na linha: $line" }

        return bytes
    }

    /**
     * Monta um buffer único do tamanho da flash a ser gravada, preenchendo
     * espaços não usados com 0xFF (valor padrão de flash apagada).
     * Útil pra gravar em páginas de tamanho fixo (ex: 128 bytes no ATmega328).
     */
    fun toFlashImage(records: List<HexRecord>, pageSize: Int = 128): ByteArray {
        val maxAddress = records.maxOf { it.address + it.data.size }
        val paddedSize = ((maxAddress + pageSize - 1) / pageSize) * pageSize
        val image = ByteArray(paddedSize) { 0xFF.toByte() }

        records.forEach { record ->
            record.data.copyInto(image, destinationOffset = record.address)
        }
        return image
    }
}
