# Skeleton: minify desligado (isMinifyEnabled = false). Regras aqui servem
# de ponto de partida para quando o release build for habilitado.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
