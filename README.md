# Label Cropper (Android)

Android version of the desktop Label Cropper. Crops Meesho and Flipkart
shipping-label PDFs for a label printer, entirely on the phone.

- **Meesho**: each page's label (address, barcode, Product Details and the
  Bill of Supply bar) and invoice (from Bill To / Ship To down) are placed
  side by side on a 5×3in page.
- **Flipkart**: two files. *Labels*, each cropped tight onto its own
  ~3×5in page. *Invoices*, every Tax Invoice found below a label, merged
  two per A4 page with a dashed cut guide. The Invoices buttons are greyed
  out if the upload has none.

Pick PDFs in the app, or use **Share → Label Cropper** from the seller app,
Files or WhatsApp. Each output has **Save**, **Share** and **Print**.
Requires Android 8.0+.

**Print** sends the labels straight to a Bluetooth thermal label printer
(e.g. SEZNIK Shakti), the way FlashLabel-style apps do, with no print
service needed. The first time, pick the printer and the label size loaded
in it; after that one tap prints. Each page becomes one label, turned to
match the label and scaled to fit. Classic Bluetooth (SPP) and BLE printers
are both supported, and TSPL (default) or ESC/POS commands. Tap the
*Printer:* line under the results to change printer, label size or
language. *Other printers* in the picker opens the Android print dialog.

## Build

Open this folder in Android Studio and press **Run**, or from a terminal:

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Gradle 8.10.2 needs a JDK 17–23 to run. If Android Studio's bundled JDK is
newer, set *Settings → Build Tools → Gradle → Gradle JDK* to a JDK 21, or
set `JAVA_HOME` to one when building from a terminal.

## Code layout

- `app/src/main/java/com/labelcropper/core/` — the cropping logic in plain
  Kotlin with no Android dependencies: page analysis (`MeeshoLayout`,
  `FlipkartLayout`), the output pipeline (`LabelCropper`) and a small
  image-only PDF writer (`PdfImageWriter`).
- `app/src/main/java/com/labelcropper/app/` — the Android screen, PDF
  rendering via the built-in `PdfRenderer`, and the share/print plumbing.

Crops are rasterized (300 DPI at their printed size) rather than copied as
vector content, because some label-printer drivers garble embedded fonts.

## Tests

```
./gradlew testDebugUnitTest
```

runs synthetic tests of the core logic. To also run it on real PDFs, render
their pages at 300 DPI into `<dir>/<pdf name>/p001.rgb, p002.rgb, ...`
(big-endian int32 width, int32 height, then packed RGB bytes) and pass
`-PfixturesDir=<dir>`; add `-PtestOutDir=<dir>` to keep the output PDFs.
