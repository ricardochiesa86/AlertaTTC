#!/usr/bin/env python3
"""
Exporta yolo11s.pt para TFLite float32, no formato que VehicleDetector.kt
espera (entrada NHWC 640x640x3 float32 0-1, saida [1, 84, 8400] ou
[1, 8400, 84] — o app detecta a orientacao sozinho em runtime).

Uso:
    pip install ultralytics
    python tools/export_model.py

Gera yolo11s_saved_model/yolo11s_float32.tflite e copia para
app/src/main/assets/model.tflite.

O nome de destino e neutro (model.tflite, sem a versao do YOLO) porque o
workflow de build no GitHub baixa o modelo de uma release e grava com
esse mesmo nome. Trocar de yolo11s para outra variante nao deve exigir
mudanca no Kotlin nem no workflow — so gerar um .tflite novo aqui.
"""
import shutil
import sys
from pathlib import Path

from ultralytics import YOLO

MODELO_PT = "yolo11s.pt"          # baixado automaticamente pela ultralytics na 1a chamada
IMGSZ = 640
DEST = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "model.tflite"


def main():
    modelo = YOLO(MODELO_PT)

    # nms=False (padrao): deixamos a filtragem de classe/confianca e a NMS
    # para o Kotlin (VehicleDetector.postprocess), que so precisa das 4
    # classes de veiculo — rodar NMS geral no modelo so gastaria tempo com
    # as outras 76 classes do COCO.
    caminho = modelo.export(format="tflite", imgsz=IMGSZ, int8=False, half=False, nms=False)
    print(f"export retornou: {caminho}")

    saved_model_dir = Path(str(MODELO_PT).replace(".pt", "")).with_name(
        Path(MODELO_PT).stem + "_saved_model"
    )
    candidato = saved_model_dir / f"{Path(MODELO_PT).stem}_float32.tflite"
    if not candidato.exists():
        print(f"Nao achei {candidato}. Veja a saida do export acima e copie manualmente")
        print(f"o .tflite float32 para {DEST}")
        sys.exit(1)

    DEST.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(candidato, DEST)
    print(f"copiado para {DEST} ({DEST.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
