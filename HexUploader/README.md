# Hex Uploader

App Android mínimo para gravar um `.hex` já compilado no Arduino via
cabo OTG, sem precisar da IDE nem de PC. Funcionalidades:

- Selecionar um arquivo `.hex` do celular (ou abrir direto de outro app,
  como um gerenciador de arquivos ou nuvem, via "Abrir com"/"Compartilhar")
- Escolher o baud rate (115200, 57600, 38400, 19200, 9600) — a última
  escolha fica salva e volta pré-selecionada na próxima vez
- Indicador de conexão em tempo real (detecta sozinho quando o
  adaptador USB é plugado/desplugado, sem precisar apertar nada)
- Verificação da assinatura do chip antes de gravar: lê o chip
  conectado, mostra o nome (ex: "ATmega328P") e pede confirmação —
  evita gravar um `.hex` no chip errado sem perceber
- Gravar no Arduino com um toque, com barra de progresso
- Monitor serial: abrir a porta em qualquer baud rate, ver os dados
  chegando em tempo real numa área rolável, e enviar texto de volta
  com o terminador de linha escolhido (nenhum, `\n` ou `\r\n`)

## Como funciona

1. Lê o `.hex` (formato Intel HEX) e o converte para os bytes reais
   de flash (`IntelHexParser.kt`).
2. Abre a porta serial do adaptador USB conectado via OTG usando a
   biblioteca `usb-serial-for-android` (suporta CH340, CP2102, FTDI e
   o chip nativo do Uno/Leonardo — veja `device_filter.xml`).
3. Faz o auto-reset do Arduino soltando o DTR (mesmo truque que o
   avrdude usa), aguarda o bootloader subir.
4. Fala STK500v1 com o bootloader — sync, entra em modo de
   programação, grava página por página, sai do modo de programação
   (`Stk500v1.kt`).
5. O monitor serial (`SerialMonitor.kt`) usa a classe
   `SerialInputOutputManager` da própria biblioteca `usb-serial-for-android`,
   que fica numa thread separada lendo a porta e entregando os dados
   assim que chegam — sem polling manual.

**Importante:** a porta USB só pode estar aberta por um lado de cada
vez. O app fecha o monitor automaticamente antes de gravar; se quiser
ver a saída serial logo depois de uma gravação, reabra o monitor
manualmente (o Arduino já reiniciou sozinho e o sketch novo já está
rodando).

## Como compilar

Precisa do Android Studio (ou gradle + Android SDK via linha de
comando). Não precisa de nenhuma conta ou chave — é tudo offline
depois de baixar as dependências.

**Opção A — Android Studio:**
1. Abra a pasta `HexUploader/` como projeto.
2. Deixe o Gradle sincronizar (baixa `usb-serial-for-android` via
   JitPack, que já está configurado no `settings.gradle`).
3. Rode direto no celular (com um cabo normal primeiro, para
   instalar o APK) ou gere o APK em
   `Build > Build Bundle(s)/APK(s) > Build APK(s)`.

**Opção B — GitHub Actions (igual você já faz no ArduFlash):**
Suba esse projeto num repo e reaproveite um workflow como:

```yaml
name: build
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4
      - name: Gerar o Gradle Wrapper
        run: gradle wrapper --gradle-version 8.7
      - run: ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/app-debug.apk
```

O `gradle/actions/setup-gradle` instala um Gradle "de sistema" no
runner; o passo `gradle wrapper` usa esse Gradle pra gerar
`gradlew`/`gradlew.bat`/`gradle-wrapper.jar` na hora, então não
precisa desses arquivos binários dentro do zip que você recebeu. A
partir daí `./gradlew assembleDebug` já funciona normalmente.

(Alternativa: se preferir manter o wrapper versionado no repo, como
o Android Studio faz por padrão, basta abrir o projeto uma vez nele —
ele cria os arquivos do wrapper sozinho — e daí commitar
`gradlew`, `gradlew.bat` e a pasta `gradle/wrapper/`.)

## Limitações desta primeira versão

- Não faz leitura/verificação da flash depois de gravar (só grava).
- A checagem de assinatura mostra o chip encontrado e pede
  confirmação, mas não impede a gravação em si — é você quem decide se
  o chip detectado bate com o `.hex` selecionado.
- Não guarda log do monitor serial em arquivo — é só para
  acompanhamento visual em tempo real, por decisão de escopo.
- Testado conceitualmente para ATmega328P/32u4 com bootloader
  Optiboot (Uno, Nano, Leonardo). Placas com bootloaders diferentes
  podem precisar de ajustes no tamanho de página (`pageSize` em
  `Stk500v1.kt`) ou no protocolo (algumas usam STK500v2 — protocolo
  diferente, não implementado nesta versão).
- A lista de VID/PID em `device_filter.xml` cobre os chips mais
  comuns, mas se seu adaptador não aparecer, é só adicionar o par
  vendor-id/product-id dele lá.
- O filtro de "Abrir com" para `.hex` é amplo (`*/*` + padrão de nome
  de arquivo) porque o `.hex` não tem um MIME type oficial — então o
  app pode aparecer em telas de compartilhamento de outros tipos de
  arquivo também. Isso é uma limitação do Android, não do app.
