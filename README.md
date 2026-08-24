# Alerta TTC — esqueleto tecnico Android

Porte do `alerta_ttc.py` para rodar ao vivo no celular. Objetivo desta
versao: provar que a tecnica (deteccao + TTC por crescimento de caixa)
sustenta pelo menos 10 fps por alguns minutos sem throttle termico, com
latencia frame->som abaixo de 200 ms, no Galaxy S20 FE (SM-G780F, Exynos
990, Mali-G77, Android 13). Nao e o produto final — interface e so o
minimo pra medir isso.

## 1. Exportacao do modelo

```bash
pip install ultralytics
python tools/export_model.py
```

Isso roda por baixo:

```bash
yolo export model=yolo11s.pt format=tflite imgsz=640 int8=False half=False nms=False
```

e copia o `.tflite` float32 resultante (de dentro de
`yolo11s_saved_model/`) para `app/src/main/assets/yolo11s.tflite`.

Por que essas flags:
- `int8=False`: quantizacao int8 do YOLO11 tem relatos de perda de
  precisao na cabeca de deteccao e nao acelera no delegate de GPU (Mali
  favorece float32/float16, o ganho do int8 e pra CPU/NNAPI). Sem NNAPI
  neste projeto, int8 nao compensa.
- `nms=False` (padrao): a filtragem de classe e a NMS ficam no Kotlin
  (`VehicleDetector.postprocess`), que so processa as 4 classes de
  veiculo — mais barato que rodar NMS geral sobre as 80 classes do COCO
  dentro do grafo do modelo.
- Se quiser um modelo menor, `half=True` gera uma variante float16; o
  delegate de GPU do LiteRT aceita, mas o path documentado e testado
  aqui e o float32.

O app assume que o `.pt` de origem e o YOLO11s stock treinado em COCO (80
classes, ordem padrao) — e por isso que os indices 2/3/5/7 (car,
motorcycle, bus, truck) fazem sentido direto no Kotlin.

## 2. Build

Abra a pasta `AlertaTTC/` no Android Studio (Koala/Ladybug ou mais
recente). Ele vai gerar o `gradlew`/wrapper automaticamente no primeiro
sync — este repositorio nao inclui o jar do wrapper.

Alternativa via linha de comando, se ja tiver o Gradle instalado:

```bash
cd AlertaTTC
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

**Antes de compilar**, confirme que `app/src/main/assets/yolo11s.tflite`
existe (passo 1) — sem ele o app abre e cai no estado de erro "erro ao
carregar modelo".

## 3. Instalar no aparelho

Com o S20 FE em modo desenvolvedor + depuracao USB ativada:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ou rode direto pelo Android Studio (Run ▶ com o aparelho selecionado).

Na primeira abertura o app pede permissao de camera (e notificacao, no
Android 13+, para a notificacao persistente do servico em primeiro
plano). Sem a permissao de camera o app nao funciona — nao ha modo
degradado.

## 4. Como ler fps e latencia durante o teste

**Na tela**: os dois numeros no topo (FPS / LATENCIA) atualizam a cada
frame processado. LATENCIA e o tempo entre o frame chegar da camera e a
decisao de TTC estar pronta (inclui letterbox + inferencia + pos-
processamento + logica de portoes) — e o numero que importa pro
orcamento de 200 ms frame-a-som; nao inclui a latencia de hardware de
audio do proprio ToneGenerator, que foge do controle do app.

**No log**, pra um teste de alguns minutos sem ficar olhando a tela:

```bash
adb logcat -s AlertaTTC-Metrics:I
```

Cada linha e um frame processado:

```
fps=11.42 latencyMs=68 ttc=1.83 alerta=false gpu=true status=monitorando
```

Pra salvar e analisar depois:

```bash
adb logcat -s AlertaTTC-Metrics:I > teste_$(date +%s).log
```

`gpu=true` confirma que o delegate de GPU pegou (senao caiu pra CPU — o
texto de status na tela tambem mostra "GPU" ou "CPU"). Pra confirmar
ausencia de throttle termico durante o teste, observe o campo `status`:
qualquer entrada em "aparelho esquentando (thermal=N)" e o
`ThermalMonitor` avisando que o `PowerManager` reportou pelo menos
`THERMAL_STATUS_MODERATE`.

## 5. O que este esqueleto NAO faz (de proposito)

- Sem preview de camera na tela durante a operacao (so numeros).
- Sem onboarding, ajustes ou tela de fim de trajeto — vem depois, a
  partir de um prototipo de design separado.
- Sem gravacao: nenhum frame ou video toca o disco.
- Sons de alerta (`AlertSoundPlayer`) sao gerados com `ToneGenerator`
  (bips sinteticos), nao arquivos de audio — troque por assets `.wav` em
  `res/raw` quando a versao de produto vier, se quiser um som mais
  elaborado. O padrao ja e propositalmente distinto entre "colisao" (3
  bips rapidos) e "indisponivel" (1 tom longo).
- A calibracao do corte do painel (`PanelCalibrator`) roda uma vez, por
  8 segundos de relogio, no arranque do servico, usando deteccao sem
  recorte. Isso substitui o "roda 300 frames, rebobina o video" do
  script offline — ao vivo nao ha o que rebobinar.

## Nota sobre as dependencias do LiteRT

O `app/build.gradle.kts` usa `com.google.ai.edge.litert:litert` /
`litert-gpu` / `litert-gpu-api` na versao `1.4.1`. O codigo Kotlin
(`VehicleDetector.kt`) importa as classes pelo pacote classico
`org.tensorflow.lite.*` (`Interpreter`, `GpuDelegate`,
`CompatibilityList`), que e o que a documentacao de migracao do Google
promete manter por compatibilidade. Se o Android Studio nao resolver
essas dependencias no sync (coordenadas Maven mudam com alguma
frequencia nesse ecossistema), confira a versao atual em
https://mvnrepository.com/artifact/com.google.ai.edge.litert e ajuste o
numero de versao — as importacoes Kotlin nao devem precisar mudar.

## Estrutura

```
app/src/main/java/com/alertattc/app/
  MainActivity.kt              tela unica, observa o StateFlow do servico
  CollisionAlertService.kt     camera (CameraX) + pipeline + notificacao
  detector/
    VehicleDetector.kt         LiteRT + delegate de GPU com fallback p/ CPU
    Letterbox.kt                letterbox 640x640 e conversao de coordenadas
    Detection.kt                 modelos de dados
  ttc/
    TtcEngine.kt                porte fiel de alerta_ttc.py (portoes de seguranca)
    PanelCalibrator.kt           porte de calibrar_corte
  audio/AlertSoundPlayer.kt    bips de colisao / indisponivel via ToneGenerator
  util/
    FpsMeter.kt, ThermalMonitor.kt, Stats.kt (mediana, regressao linear, deque com janela variavel)
tools/export_model.py          wrapper do comando de export
```
