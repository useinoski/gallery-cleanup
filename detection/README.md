# :detection

Owned by the ML specialist.

Week 1 ships the contract only: `MediaDetector` and `DetectionResult`. There is no model, no interpreter, and no ML Kit dependency.

When a model is added, use the bundled on-device artifacts:

- `com.google.mlkit:face-detection`
- `com.google.mlkit:image-labeling`

Do not use `com.google.android.gms:play-services-mlkit-*`. The Play Services variants download models and can open a network connection. Gallery Cleanup does not have `INTERNET`; the app manifest removes it, and `:app:checkDebugMergedManifest` fails the build if a dependency puts it back.

`classify` receives a `Bitmap` that already lives in memory. Do not write it to a server, a content provider other than this app's own private storage, or a log.
