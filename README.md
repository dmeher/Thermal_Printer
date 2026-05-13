# BT Thermal Print

Minimal Android app inspired by RawBT for Bluetooth thermal printers.

## What it does

- Appears in the Android share sheet for text, images, and PDFs.
- Lists paired Bluetooth printers and scans for nearby Classic Bluetooth devices.
- Connects over Bluetooth SPP.
- Prints:
  - shared text as ESC/POS text
  - shared images as ESC/POS raster bitmap
  - shared PDFs by rendering each page to bitmap and sending raster data
- Supports 58mm and 80mm paper widths.
- Remembers the last selected printer address.

## Expected printer type

This first version targets common Classic Bluetooth ESC/POS thermal printers.
Most 58mm/80mm receipt printers use this mode.

BLE-only printers or vendor-specific printer SDKs may need a different transport layer.

## Build

Open this folder in Android Studio:

```text
C:\Users\meher\Documents\Projects\BtThermalPrint
```

Then run:

```text
Build > Make Project
```

or build `app` from Android Studio.

Command-line build:

```text
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

## Test flow

1. Pair the thermal printer in Android Bluetooth settings.
2. Install and open the app.
3. Allow Bluetooth permissions.
4. Select the printer.
5. Share a PDF, image, or text file from another app.
6. Choose `BT Thermal Print`.
7. Tap `Print shared document`.

## Notes

- Some printers do not support UTF-8 text well. Images and PDFs should still print because they are sent as raster data.
- If the printer appears during scan but connection fails, pair it in Android settings first.
- PDF printing converts pages to bitmap, so very large PDFs may print slowly.
