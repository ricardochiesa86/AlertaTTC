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
`yolo11s_saved_model/`) para `app/src/main/assets/model.tflite`.

O nome do asset e **neutro de proposito** — `model.tflite`, sem a versao
do YOLO. O workflow de build no GitHub
(`.github/workflows/build.yml`) baixa o modelo de uma release e grava
com esse mesmo nome, entao trocar de `yolo11s` para outra variante nao
exige mudanca no Kotlin (`VehicleDetector.assetPath`) nem no workflow.

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

**Antes de compilar localmente**, confirme que
`app/src/main/assets/model.tflite` existe (passo 1) — sem ele o app abre
e cai no estado de erro "erro ao carregar modelo". No build do GitHub o
workflow baixa esse arquivo da release antes de compilar, entao la o
passo 1 nao e necessario.

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

## 5. Diagnosticar por que o TTC nao calcula

Duas ferramentas existem especificamente pra responder "em qual etapa a
cadeia deteccao → lider → TTC quebra":

**Preview com overlay.** Botao "Ver camera" na tela principal (desligado
por padrao — a tela de operacao fica limpa e o custo extra de copiar
bitmap por frame so existe enquanto ligado). Mostra o mesmo frame que o
detector analisou, com: caixa por classe de veiculo (cor distinta por
classe), o lider destacado e rotulado "LIDER", a linha vermelha do corte
do painel calibrado, as duas linhas verticais do terco central (33%-67%
— a faixa que o filtro de lider aceita) e a largura em pixels da caixa
do lider. Se um veiculo aparece na tela mas nunca fica marcado como
lider, ou fica marcado mas fora das linhas do terco central, e visual
imediatamente.

**Log de diagnostico**, tag separada do `AlertaTTC-Metrics`:

```bash
adb logcat -s AlertaTTC-Debug:D
```

Registra a cada 10 frames (constante `DEBUG_LOG_EVERY_N_FRAMES` em
`CollisionAlertService`, pra nao pesar no fps): dimensoes do frame e
rotacao aplicada; corte do painel em uso (fracao e pixels); todas as
deteccoes brutas do modelo (classe, confianca, caixa, largura, centro
X); quantas sobraram apos o filtro de largura e apos o filtro do terco
central; se nao houve lider, o motivo exato (`nenhuma deteccao` /
`todas largas demais` / `todas fora do terco central`); se houve lider,
largura suavizada, derivada em px/s, deriva lateral, e — quando o TTC
nao sai — o motivo exato (largura abaixo do minimo, serie curta,
aproximacao insuficiente, ou deriva lateral acima do limite). A logica
e os limiares em si (`TtcEngine`) nao mudaram nem um pouco — isto e so
instrumentacao por cima do que ja existia.

Um teste de campo tipico: ligar o preview, apontar pra um veiculo a
frente, e ver ao vivo se a caixa aparece, se cai dentro das linhas do
terco central, e se a largura cresce (indicando aproximacao) — depois
cruzar com o log pra ver exatamente qual portao esta barrando.

## 6. Corte do painel: sem painel visivel = sem corte

O corte existe so pra remover o painel do proprio carro do quadro (o
YOLO classifica o painel como veiculo com confianca alta). Se a
calibracao automatica (`PanelCalibrator`, roda uma vez nos primeiros ~8s)
nao encontra o padrao de painel (< 30% dos frames com uma caixa larga
ancorada embaixo), a conclusao e "nao ha painel" — e o corte fica em
1.0, ou seja, quadro inteiro, sem descartar nada. Isso importa porque
celular a pe, suporte mais alto, carro com capo baixo ou teste em
bancada nao tem painel visivel, e cortar sem motivo destrói justamente a
area de baixo do quadro — onde aparece o veiculo mais perto.

O status na tela e o log sempre dizem qual dos dois casos esta valendo:

```
painel encontrado — corte calibrado em 0.62
painel nao encontrado — quadro inteiro (corte 1.00)
corte desativado manualmente — quadro inteiro (corte 1.00)
```

**Recalibrar**: botao "Recalibrar" na tela principal, ao lado de "Ver
camera" e "Ajustes". Util se o suporte ou o carro mudou depois que a
calibracao ja concluiu (por exemplo, concluiu "sem painel" mas agora ha
um painel visivel, ou vice-versa) — forca uma nova rodada de calibracao
de ~8s a partir do proximo frame, sem precisar reiniciar o app.

**Desativar corte manualmente**: switch em Ajustes → Diagnostico
("Desativar corte do painel"). Forca quadro inteiro
independentemente do que a calibracao concluiu — serve pra isolar, em
teste, se o corte esta atrapalhando a deteccao. Padrao: desligado
(calibracao automatica no comando). Fica em `SharedPreferences`
(`CalibrationPrefs`) e e lido a cada frame, entao o efeito e imediato
com o servico ja rodando.

## 7. Sons

Tres categorias, desenhadas pra nunca serem confundidas ao ouvir —
divergem em volume, cadencia e duracao ao mesmo tempo, nao so no tom:

- **Colisao**: volume maximo, staccato repetido (3 padroes escolhiveis
  nos ajustes), ~1-1.5s. E o "aja agora".
- **Indisponivel**: volume medio, um unico tom grave sustentado, ~0.9s.
  Toca quando a camera para, o aparelho esquenta ou a deteccao cai — e
  "parei de te proteger", nao "freie".
- **Confirmacao**: volume baixo, um tom curto (~0.15s), toca uma vez
  quando o monitoramento comeca de verdade (calibracao concluida).

Tela de ajustes (botao "Ajustes" na tela principal, usar com o carro
parado): amostra de cada som, escolha entre as 3 variantes de som de
colisao, e controle de volume — tudo com "tocar amostra" ali mesmo.
Preferencias ficam em `SharedPreferences` (`AlertPrefs`) e sao lidas a
cada disparo real de alerta, entao mudar nos ajustes com o servico ja
rodando tem efeito imediato no proximo alerta.

## 8. O que este esqueleto NAO faz (de proposito)

- Sem onboarding nem tela de fim de trajeto — vem depois, a partir de um
  prototipo de design separado. Ajustes existe, mas so o necessario pra
  testar os sons e isolar o corte do painel.
- Sem gravacao: nenhum frame ou video toca o disco, nem com o preview
  ligado (o bitmap copiado pro preview vive so em memoria).
- Sons de alerta (`AlertSoundPlayer`) sao gerados com `ToneGenerator`
  (bips sinteticos), nao arquivos de audio — troque por assets `.wav` em
  `res/raw` quando a versao de produto vier, se quiser um som mais
  elaborado.
- A calibracao do corte do painel (`PanelCalibrator`) roda uma vez, por
  8 segundos de relogio, no arranque do servico (ou quando "Recalibrar"
  e tocado), usando deteccao sem recorte. Isso substitui o "roda 300
  frames, rebobina o video" do
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
  MainActivity.kt              tela principal, observa o StateFlow do servico
  SettingsActivity.kt          ajustes de som + diagnostico (Material 3, carro parado)
  CollisionAlertService.kt     camera (CameraX) + pipeline + notificacao + preview/debug
  ui/DetectionOverlayView.kt   desenha o overlay de diagnostico sobre o frame analisado
  detector/
    VehicleDetector.kt         LiteRT + delegate de GPU com fallback p/ CPU
    Letterbox.kt                letterbox 640x640 e conversao de coordenadas
    Detection.kt                 modelos de dados
  ttc/
    TtcEngine.kt                porte fiel de alerta_ttc.py + diagnostico (motivo por portao)
    PanelCalibrator.kt           porte de calibrar_corte; sem painel = sem corte
    CalibrationPrefs.kt          switch manual "desativar corte", em SharedPreferences
  audio/
    AlertSoundPlayer.kt        os 3 sons (colisao/indisponivel/confirmacao) via ToneGenerator
    AlertPrefs.kt               volume e variante do som de colisao, em SharedPreferences
  util/
    FpsMeter.kt, ThermalMonitor.kt, Stats.kt (mediana, regressao linear, deque com janela variavel)
tools/export_model.py          wrapper do comando de export
```
