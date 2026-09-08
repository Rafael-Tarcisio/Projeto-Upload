# ArduFlash — esqueleto do app

App Android (Kotlin) pra gravar `.hex` num Arduino Uno via OTG, sem notebook,
com aba de monitor serial embutida.

## Como gerar o APK SEM Android Studio (via GitHub Actions)

1. Crie uma conta gratuita em https://github.com (se ainda não tiver)
2. Crie um repositório novo, público ou privado (botão "New" no site)
3. Faça upload de TODOS os arquivos desse zip pro repositório:
   - Na página do repositório, clique em "Add file" → "Upload files"
   - Arraste a pasta inteira (ou os arquivos extraídos do zip) — inclusive
     a pasta oculta `.github/`, que contém o workflow de build
   - Confirme o commit ("Commit changes")
4. Vá na aba **"Actions"** do repositório — o build deve começar sozinho
   automaticamente após o upload (leva uns 3-5 minutos)
5. Quando o ícone ficar verde (✅), clique no build concluído
6. Role até "Artifacts" e baixe **ArduFlash-debug-apk** (vem como .zip,
   com o `app-debug.apk` dentro)
7. Transfira esse `.apk` pro celular (Google Drive, WhatsApp pra você
   mesmo, cabo USB) e abra o arquivo lá pra instalar
   (o Android vai pedir permissão de "fontes desconhecidas" — é normal)

Pronto — build feito 100% pelo navegador, sem instalar nada no PC.

## Alternativa: Android Studio (só se quiser editar/depurar o código)
1. Abra a pasta `ArduFlash/` inteira no Android Studio (File > Open).
2. Deixe o Gradle sincronizar (ele vai baixar `usb-serial-for-android` via JitPack).
3. Rode num celular físico (emulador não tem porta USB de verdade) com cabo OTG.

## Estrutura

| Arquivo | Responsabilidade |
|---|---|
| `IntelHexParser.kt` | Decodifica o `.hex` (Intel HEX) em bytes + endereços |
| `UsbSerialManager.kt` | Abre a conexão OTG, pulsa DTR (reset), lê/escreve bytes crus |
| `Stk500Flasher.kt` | Implementa o handshake STK500v1 (sync, entrar/sair modo programação, escrever páginas) |
| `MainActivity.kt` | UI: escolher arquivo, conectar, gravar, e um console que serve tanto de log de gravação quanto de monitor serial |

## Fluxo de gravação
```
Escolher .hex → Conectar (pede permissão OTG) → Gravar
                                                    ↓
                                    pulseResetViaDtr() [reinicia bootloader]
                                                    ↓
                                    syncWithRetries() [handshake STK500]
                                                    ↓
                                    ENTER_PROGMODE
                                                    ↓
                                    loadAddress() + programPage() por página de 128B
                                                    ↓
                                    LEAVE_PROGMODE
```

## O que falta pra virar produto (próximos passos)
- [ ] Testar timing do `pulseResetViaDtr()` — bootloaders variam um pouco no
      tempo de espera após reset; pode precisar de ajuste fino (Optiboot
      aceita ~1s de janela, mas cabos/chips diferentes variam).
- [ ] Detectar automaticamente o baud rate certo (115200 pro Optiboot padrão
      do Uno R3; bootloaders antigos podem usar 57600).
- [ ] Tratar erro de "placa não respondeu" com uma mensagem mais amigável
      (hoje só lança exceção genérica).
- [ ] Persistir o último `.hex` usado (pra não precisar escolher de novo
      toda vez que abrir o app).
- [ ] Opcional: assinar o `.hex` com HMAC antes de permitir gravação,
      reaproveitando a lógica de segurança que você já usa no EVSE.
- [ ] Polish de UI: separar visualmente o log de "gravação" do log de
      "monitor serial" (hoje estão no mesmo console por simplicidade).

## Observação importante
Esse protocolo (STK500 via bootloader) só funciona se o Uno **já tiver
bootloader intacto** — é diferente do ICSP puro que discutimos antes pro
projeto do ESP32 fixo. São duas soluções complementares, não substitutas.
