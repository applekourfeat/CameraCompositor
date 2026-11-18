"""MobileNet NIMA architecture from idealo, Apache-2.0. Research use only.

The fixed-revision weights are loaded completely, without ImageNet downloads or training.
Input preprocessing matches idealo's predict generator: RGB, 224x224, [-1, 1].
"""
from __future__ import annotations
import os
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "2")
os.environ.setdefault("TF_NUM_INTEROP_THREADS", "1")
import numpy as np
from PIL import Image
from fetch_assets import CACHE


class NimaScorer:
    name = "nima-mobilenet"

    def __init__(self):
        import tensorflow as tf
        self.tf = tf
        base = tf.keras.applications.MobileNet(input_shape=(224, 224, 3), include_top=False,
                                               pooling="avg", weights=None)
        output = tf.keras.layers.Dense(10, activation="softmax")(tf.keras.layers.Dropout(0)(base.output))
        self.model = tf.keras.Model(base.inputs, output)
        self.model.load_weights(str(CACHE / "nima-aesthetic.hdf5"))
        self.model(np.zeros((1, 224, 224, 3), dtype=np.float32), training=False)

    @staticmethod
    def prepare(image: Image.Image, boxes: list[list[float]]) -> np.ndarray:
        samples = []
        for left, top, right, bottom in boxes:
            crop = image.crop((round(left * image.width), round(top * image.height),
                               round(right * image.width), round(bottom * image.height)))
            # idealo uses Keras/PIL nearest-neighbor resizing; preserve that preprocessing.
            samples.append(np.asarray(crop.resize((224, 224), Image.Resampling.NEAREST), dtype=np.float32) / 127.5 - 1)
        return np.stack(samples)

    def score(self, image: Image.Image, boxes: list[list[float]]) -> np.ndarray:
        distribution = np.asarray(self.model(self.prepare(image, boxes), training=False))
        return distribution @ np.arange(1, 11, dtype=np.float32)

    def export(self, path):
        converter = self.tf.lite.TFLiteConverter.from_keras_model(self.model)
        converter.optimizations = [self.tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [self.tf.float16]
        path.write_bytes(converter.convert())


if __name__ == "__main__":
    model = NimaScorer()
    image = Image.open(CACHE.parents[0] / "reference-frames" / "scene-clean.jpg").convert("RGB")
    print(model.score(image, [[0, 0, 1, 1]]).tolist())
