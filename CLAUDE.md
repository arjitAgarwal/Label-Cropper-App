# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

Gradle 8.10.2 / AGP 8.7.3 / Kotlin 2.0.21 need a JDK 17–23. On this machine Android Studio's bundled JBR is Java 25, which fails with just "25.0.3", so set `JAVA_HOME` to a JDK 21 (e.g. `C:\Users\Arjit\.jdks\jbr-21.0.11`) before running Gradle.

```
./gradlew assembleDebug                 # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest             # JVM unit tests (core logic only)
./gradlew testDebugUnitTest --tests "com.labelcropper.core.ThermalCommandsTest"
./gradlew testDebugUnitTest --tests "com.labelcropper.core.LabelCropperTest.meeshoSplitsBelowGreyBar"
```

There is no lint or formatter step configured, and no instrumented (on-device) tests.

`LabelCropperTest` has synthetic tests that always run, plus fixture tests that are skipped unless `-PfixturesDir=<dir>` is passed. Fixtures are pages pre-rendered at 300 DPI as `<dir>/<pdf name>/p001.rgb, p002.rgb, ...` (big-endian int32 width, int32 height, then packed RGB bytes). `-PtestOutDir=<dir>` keeps the output PDFs.

## Architecture

Single-module, single-screen app that crops Meesho and Flipkart shipping-label PDFs for thermal label printers, entirely offline. It is an Android port of an earlier desktop (Python/pdfplumber) Label Cropper; comments refer to it as "the desktop app".

The app has **no library dependencies**: it uses the framework `Activity` (not AppCompat/AndroidX), `AlertDialog`, `PdfRenderer` and a hand-written PDF writer. Keep it that way unless there's a strong reason.

### `core/` — plain Kotlin, no Android imports

Everything here is JVM-testable. The pipeline:

1. A `PageSource` renders each page to a `GrayImage` at `LabelCropper.RENDER_DPI` (300).
2. Layout detection works on **rendered pixels**, not PDF vector data, because `PdfRenderer` can't expose vectors. `MeeshoLayout` finds full-width horizontal rules and anchors the label/invoice split on the shaded "Bill of Supply" bar. `FlipkartLayout` works on an `InkMask` and finds the dashed cut line between the label and the Tax Invoice.
3. `LabelCropper.cropMeesho` / `cropFlipkart` each write a labels PDF and an invoices PDF on fixed output pages (Meesho labels 5×3in, Flipkart labels ~3×5in; invoices for both are stacked 2-up on A4 by `InvoiceSheets`). `RenderedPage.crop` re-renders the region at its output size rather than downsampling the 300 DPI page, to keep small text sharp.
4. `PdfImageWriter` writes **image-only** PDFs, streamed page by page. Output is rasterized on purpose: some label-printer drivers garble embedded fonts.

`ThermalCommands` encodes 1-bit `MonoImage`s as TSPL (default) or ESC/POS for direct Bluetooth printing. TSPL's `BITMAP` prints **0** bits, so data is inverted relative to `MonoImage` (where set = black).

### `app/` — Android glue

- `MainActivity` owns all UI and state. Long work runs on a single-thread `worker` executor and posts back via `main`; a `busy` flag gates actions. The activity declares `configChanges` and is never recreated on rotation, so wide-screen centring is done at runtime in `applySystemBarInsets` rather than with resource qualifiers alone.
- `CropJob` copies the picked/shared content URIs into `cacheDir/in`, runs the cropper via `PdfRendererSource`, and writes outputs to `cacheDir/out`. `OutputProvider` is a minimal read-only provider (not FileProvider) serving only files in that directory, for Share and the print system.
- Printing has two paths:
  - **Direct Bluetooth** (the Print button): `LabelPrintJob` renders each output page at 203 DPI, rotated to match the label orientation and fitted to the chosen `LabelSize`. It sends the result through `BluetoothPrinter`, which tries classic SPP (three socket strategies) and falls back to BLE GATT, writing to a known or first-writable characteristic. `PrinterPicker` lists paired devices plus BLE scan results. Printer address, label size and language are stored in the `settings` SharedPreferences.
  - **System print**: `PdfPrintAdapter`, reached via "Other printers" in the picker.
- After a successful Bluetooth print, that card's Print button is disabled. Once **every** output on screen has printed, `cleanUpAfterPrinting` deletes the user's original input PDFs, any copies saved with Save, and `cacheDir/out`, then resets the UI. This is intentional (the user asked for it). Be careful when changing the print/save flow.

## Git workflow

Work happens on `develop`; `master` is the default/release branch on GitHub (`origin`), and `develop` is merged into it when asked.
