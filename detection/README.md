# :detection

On-device image detectors behind `MediaDetector`. Nothing in this module opens a socket. The manifest removes `INTERNET` and `ACCESS_NETWORK_STATE`, and the app manifest removes them again.

## Detectors

| Class | id | What a score means |
| --- | --- | --- |
| `MarqoNsfwDetector` | `marqo-nsfw-384` | `NSFW` and `SFW` probabilities. Softmax of the model's two logits. |
| `FaceCountDetector` | `mlkit-face-count` | `face_count`. A count, not a probability and not an identity. |
| `ImageLabelCategoryDetector` | `mlkit-image-labels-v1` | `alcohol`, `gambling`, `money`. `meme` only when the caller asks. |

`inputSizePx` is 384 for the Marqo model and 0 for the other two. 0 means "do not squash this into a square". Every detector scales the bitmap itself. The bitmap handed to `classify` must already be upright.

`DetectionResult.modelVersion` names the model and the preprocessing. It does not name the decision threshold. `DetectionScoring.categoryExceeds` compares a stored score with a threshold the caller passes in. There is no default threshold.

`DetectionScoring.reviewIdentity` is `detectorId|modelVersion`. Store that next to the user's decision. A new model changes the string. A new threshold does not, because the scores are still the ones the user saw.

## Marqo model

`src/main/assets/marqo_vit_tiny_384_int8.tflite` is an int8 conversion of [Marqo/nsfw-image-detection-384](https://huggingface.co/Marqo/nsfw-image-detection-384) (Apache-2.0 code and weights). The interpreter reports:

- input `args_0`: float32, NCHW, `[1, 3, 384, 384]`
- output `output_0`: float32 logits, `[1, 2]`
- labels, from `config.json` `label_names`: index 0 `NSFW`, index 1 `SFW`

Preprocessing follows the same config: center square (`crop_pct` 1.0, `crop_mode` center), RGB, `(pixel / 255 - 0.5) / 0.5`. Android's filtered scale is bilinear. The config asks for bicubic. That gap is left for the threshold tuning pass.

The interpreter is created once and reused. It tries the GPU delegate, then NNAPI, then CPU with XNNPACK. `backendName` says which one ran. That name is not part of `modelVersion`.

## ML Kit

Bundled artifacts only:

- `com.google.mlkit:face-detection:16.1.7`
- `com.google.mlkit:image-labeling:17.0.9`

Do not add `com.google.android.gms:play-services-mlkit-*`. The face-detection POM does pull the thin `play-services-mlkit-face-detection` client. The model files are in the `com.google.mlkit` AAR (`assets/models_bundled`), not in that client. Image labeling's base model is `assets/mlkit_label_default_model` inside its own AAR.

Face options are fast mode, no landmarks, no contours, no classification, no tracking. The result is the list size.

Label names were checked against the published base-model list. Alcohol is Alcohol, Wine, Bar, Nightclub. Gambling is Casino. Money is a separate score; `LabelCategories.gamblingScore` takes a weight the caller chooses. Screenshot, Comics, and Poster are the optional meme hint. The list has no smoking or drug label, so those are not mapped.

## Notices

`ThirdPartyNotices` and `assets/open_source_notices.txt`. `ThirdPartyNotices.readAsset(context)` is what a future screen can show.

## Tests

```bash
./gradlew :detection:test
./gradlew :detection:lintDebug
./gradlew :detection:connectedDebugAndroidTest
```

`test` is JVM only. It covers the label map, the score and threshold rules, the version strings, and the hash of the `.tflite` file. `connectedDebugAndroidTest` needs a phone. It runs all three detectors on `src/androidTest/assets/benign_landscape.png` and checks the test process has no network permission.
