"""
Обучение классификатора «работа / семья / (другое)» для PhotoSorter.

Всё выполняется на вашем компьютере: фото читаются из указанных папок только на чтение,
никуда не копируются и не отправляются. На выходе — два небольших файла для приложения:
photo_model.tflite и photo_labels.txt.

Пример (PowerShell, из папки training):
    python train_model.py --work "\\\\fs.talan.udm.ru\\...\\ФОТО" --family "C:\\Users\\yangirov\\Documents\\Фотографии"

Необязательно: --other "папка с примерами «другого»" (мемы, еда, скриншоты чатов и т. п.).
Без неё модель знает только «работа» и «семья», а всё остальное приложение отправляет в «Другое»
по низкой уверенности.
"""
import argparse
import os
import random
import shutil
import sys
from pathlib import Path

import numpy as np

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SIZE = 224
EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def find_images(root: Path):
    found = []
    for dirpath, _dirs, files in os.walk(root, onerror=lambda e: print(f"  нет доступа: {e}")):
        for name in files:
            if Path(name).suffix.lower() in EXTS:
                found.append(Path(dirpath) / name)
    return sorted(found)


def load_image(path: Path):
    from PIL import Image, ImageOps

    try:
        with Image.open(path) as im:
            if im.format == "JPEG":
                im.draft("RGB", (SIZE * 2, SIZE * 2))   # быстрее читать большие JPEG
            im = ImageOps.exif_transpose(im).convert("RGB")
            # Без сохранения пропорций — так же, как в приложении (Bitmap.createScaledBitmap)
            im = im.resize((SIZE, SIZE), Image.BILINEAR)
            return np.asarray(im, dtype=np.uint8)
    except Exception as e:  # битый файл не должен ронять обучение
        print(f"  пропуск {path.name}: {e}")
        return None


def load_class(name: str, folder: str, max_per_class: int, rng: random.Random):
    root = Path(folder)
    if not root.is_dir():
        sys.exit(f"Папка «{name}» не найдена или недоступна: {folder}")
    files = find_images(root)
    print(f"[{name}] найдено фото: {len(files)}")
    if len(files) > max_per_class:
        files = sorted(rng.sample(files, max_per_class))
    arrays = []
    for i, p in enumerate(files, 1):
        a = load_image(p)
        if a is not None:
            arrays.append(a)
        if i % 100 == 0:
            print(f"  [{name}] загружено {i}/{len(files)}")
    if len(arrays) < 20:
        sys.exit(f"В классе «{name}» меньше 20 читаемых фото — для обучения этого мало.")
    if len(arrays) < 60:
        print(f"  ВНИМАНИЕ: в классе «{name}» всего {len(arrays)} фото, точность будет низкой.")
    return np.stack(arrays)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", required=True, help="папка с фото стройплощадки")
    ap.add_argument("--family", required=True, help="папка с семейными фото")
    ap.add_argument("--other", help="папка с примерами «другого» (необязательно)")
    ap.add_argument("--max-per-class", type=int, default=400, help="сколько фото брать из каждой папки (по умолчанию 400)")
    ap.add_argument("--out", default="out", help="куда сложить результат (по умолчанию training/out)")
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--finetune-epochs", type=int, default=6)
    ap.add_argument("--copy-to-app", help="папка assets приложения: сюда скопируются готовые файлы")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--no-pretrained", action="store_true", help="для проверки скрипта: без загрузки весов ImageNet")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    classes = [("work", args.work), ("family", args.family)]
    if args.other:
        classes.append(("other", args.other))
    labels = [c[0] for c in classes]

    # Загрузка. Из каждой папки берётся случайная выборка, затем последние 20 % по имени файла
    # уходят в проверочную часть: камера называет файлы по дате, поэтому проверка идёт на более
    # поздних снимках, а не на соседних кадрах той же серии (так оценка честнее).
    xs_tr, ys_tr, xs_va, ys_va = [], [], [], []
    for idx, (name, folder) in enumerate(classes):
        arr = load_class(name, folder, args.max_per_class, rng)
        cut = int(len(arr) * 0.8)
        xs_tr.append(arr[:cut]); ys_tr += [idx] * cut
        xs_va.append(arr[cut:]); ys_va += [idx] * (len(arr) - cut)
    x_tr, y_tr = np.concatenate(xs_tr), np.array(ys_tr)
    x_va, y_va = np.concatenate(xs_va), np.array(ys_va)
    print(f"Обучение: {len(x_tr)} фото, проверка: {len(x_va)} фото, классы: {labels}")

    import tensorflow as tf
    from tensorflow import keras
    from tensorflow.keras import layers

    keras.utils.set_random_seed(args.seed)

    base = keras.applications.MobileNetV2(
        input_shape=(SIZE, SIZE, 3), include_top=False, pooling="avg",
        weights=None if args.no_pretrained else "imagenet",
    )
    base.trainable = False

    # Нормализация внутри модели: приложение подаёт «сырые» значения 0..255
    inputs = keras.Input((SIZE, SIZE, 3), name="image")
    x = layers.Rescaling(1 / 127.5, offset=-1.0)(inputs)
    x = base(x, training=False)
    x = layers.Dropout(0.2)(x)
    outputs = layers.Dense(len(labels), activation="softmax", name="probs")(x)
    model = keras.Model(inputs, outputs)

    aug = keras.Sequential([
        layers.RandomFlip("horizontal"),
        layers.RandomRotation(0.05),
        layers.RandomZoom(0.1),
        layers.RandomContrast(0.15),
        layers.RandomBrightness(0.15, value_range=(0, 255)),
    ])

    def make_ds(x, y, train):
        ds = tf.data.Dataset.from_tensor_slices((x, y))
        if train:
            ds = ds.shuffle(len(x), seed=args.seed)
        ds = ds.batch(32)
        if train:
            ds = ds.map(lambda a, b: (aug(tf.cast(a, tf.float32), training=True), b))
        else:
            ds = ds.map(lambda a, b: (tf.cast(a, tf.float32), b))
        return ds.prefetch(tf.data.AUTOTUNE)

    ds_tr, ds_va = make_ds(x_tr, y_tr, True), make_ds(x_va, y_va, False)

    counts = np.bincount(y_tr, minlength=len(labels))
    class_weight = {i: float(len(y_tr) / (len(labels) * max(c, 1))) for i, c in enumerate(counts)}

    stop = keras.callbacks.EarlyStopping(monitor="val_loss", patience=3, restore_best_weights=True)
    model.compile(optimizer=keras.optimizers.Adam(1e-3),
                  loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    print("Этап 1: обучение верхнего слоя")
    model.fit(ds_tr, validation_data=ds_va, epochs=args.epochs, class_weight=class_weight, callbacks=[stop], verbose=2)

    if args.finetune_epochs > 0:
        print("Этап 2: дообучение верхних блоков сети")
        base.trainable = True
        for layer in base.layers[:-30]:
            layer.trainable = False
        model.compile(optimizer=keras.optimizers.Adam(1e-5),
                      loss="sparse_categorical_crossentropy", metrics=["accuracy"])
        model.fit(ds_tr, validation_data=ds_va, epochs=args.finetune_epochs, class_weight=class_weight,
                  callbacks=[stop], verbose=2)

    # Оценка на проверочных фото
    probs = model.predict(ds_va, verbose=0)
    pred = probs.argmax(axis=1)
    conf = probs.max(axis=1)
    print("\n=== Результат на проверочных фото (их модель не видела) ===")
    print(f"Общая точность: {(pred == y_va).mean():.1%}")
    for i, name in enumerate(labels):
        m = y_va == i
        if m.any():
            print(f"  «{name}»: распознано верно {(pred[m] == i).mean():.1%} из {m.sum()}")
    print("Матрица ошибок (строки — правда, столбцы — ответ модели):")
    print("            " + " ".join(f"{n:>8}" for n in labels))
    for i, name in enumerate(labels):
        row = [int(((y_va == i) & (pred == j)).sum()) for j in range(len(labels))]
        print(f"  {name:>8}  " + " ".join(f"{v:>8}" for v in row))
    print("Если требовать уверенность не ниже порога (остальное уйдёт в «Другое»):")
    for t in (0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95):
        m = conf >= t
        if m.any():
            print(f"  порог {t:.2f}: берём {m.mean():.0%} фото, из них верно {(pred[m] == y_va[m]).mean():.1%}")
        else:
            print(f"  порог {t:.2f}: ни одно фото не проходит")

    # Экспорт в TensorFlow Lite и проверка, что результат совпадает с исходной моделью
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    tflite_bytes = tf.lite.TFLiteConverter.from_keras_model(model).convert()
    (out / "photo_model.tflite").write_bytes(tflite_bytes)
    (out / "photo_labels.txt").write_text("\n".join(labels) + "\n", encoding="utf-8")

    interp = tf.lite.Interpreter(model_content=tflite_bytes)
    interp.allocate_tensors()
    sample = x_va[:1].astype(np.float32)
    interp.set_tensor(interp.get_input_details()[0]["index"], sample)
    interp.invoke()
    lite = interp.get_tensor(interp.get_output_details()[0]["index"])
    diff = float(np.abs(lite - model.predict(sample, verbose=0)).max())
    print(f"\nПроверка TFLite: максимальное расхождение с исходной моделью {diff:.5f}")
    if diff > 1e-3:
        sys.exit("TFLite-модель расходится с исходной — файлы не готовы к использованию.")

    print(f"Готово: {out / 'photo_model.tflite'} ({len(tflite_bytes) / 1e6:.1f} МБ), {out / 'photo_labels.txt'}")
    if args.copy_to_app:
        dst = Path(args.copy_to_app)
        dst.mkdir(parents=True, exist_ok=True)
        shutil.copy(out / "photo_model.tflite", dst / "photo_model.tflite")
        shutil.copy(out / "photo_labels.txt", dst / "photo_labels.txt")
        print(f"Файлы скопированы в {dst}")


if __name__ == "__main__":
    main()
