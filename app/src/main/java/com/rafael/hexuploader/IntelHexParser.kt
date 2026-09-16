package com.rafael.hexuploader

/**
 * Parser simples de arquivos Intel HEX (.hex), o formato que o compilador
 * do Arduino gera. Suporta os tipos de registro mais comuns:
 *   00 = dado
 *   01 = fim de arquivo
 *   02/04 = deslocamento de endereço (extended segment/linear address)
 *
 * O resultado é um único array de bytes representando a memória flash,
 * já na ordem correta de endereços — pronto para ser fatiado em páginas
 * e enviado pelo STK500v1.
 */
object IntelHexParser {

    class HexParseException(message: String) : Exception(message)

    fun parse(hexText: String): ByteArray {
        val memory = sortedMapOf<Int, Int>() // endereço -> byte
        var extendedAddress = 0

        hexText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                if (!line.startsWith(":")) return@forEach
                val bytes = hexLineToBytes(line)

                val byteCount = bytes[0]
                val addrHigh = bytes[1]
                val addrLow = bytes[2]
                val recordType = bytes[3]
                val baseAddr = (addrHigh shl 8) or addrLow

                when (recordType) {
                    0x00 -> { // dado
                        for (i in 0 until byteCount) {
                            val fullAddr = extendedAddress + baseAddr + i
                            memory[fullAddr] = bytes[4 + i]
                        }
                    }
                    0x01 -> return@forEach // fim de arquivo
                    0x02 -> { // extended segment address
                        val segment = (bytes[4] shl 8) or bytes[5]
                        extendedAddress = segment * 16
                    }
                    0x04 -> { // extended linear address
                        val upper = (bytes[4] shl 8) or bytes[5]
                        extendedAddress = upper shl 16
                    }
                    else -> { /* ignora outros tipos (05 = start addr, etc.) */ }
                }
            }

        if (memory.isEmpty()) throw HexParseException("Nenhum dado de programa encontrado no .hex")

        val maxAddr = memory.keys.max()
        val result = ByteArray(maxAddr + 1)
        // Preenche vazios com 0xFF (valor padrão de flash apagada)
        for (i in result.indices) result[i] = 0xFF.toByte()
        for ((addr, value) in memory) result[addr] = value.toByte()
        return result
    }

    private fun hexLineToBytes(line: String): IntArray {
        val payload = line.substring(1)
        if (payload.length % 2 != 0) throw HexParseException("Linha .hex com tamanho inválido: $line")
        val bytes = IntArray(payload.length / 2)
        for (i in bytes.indices) {
            val hex = payload.substring(i * 2, i * 2 + 2)
            bytes[i] = hex.toInt(16)
        }
        // validação simples de checksum
        val sum = bytes.sum() and 0xFF
        if (sum != 0) throw HexParseException("Checksum inválido na linha: $line")
        return bytes
    }
}
