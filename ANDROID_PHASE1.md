# ClixSell on Android: AI Product Identification, Phase 1

Phase 1 identifies products by **barcode first, inventory first**:

1. The phone camera reads the barcode. Google ML Kit runs on the phone, through `mobile_scanner`, and needs no internet.
2. The backend looks the barcode up in the logged-in user's own store. The lookup only reads; nothing is created.
3. The real product is shown, and the cashier confirms it.

Phase 1 calls no cloud AI. Identifying a product from a photo is Phase 2, which is not implemented yet.

## What the phone app does

| Situation | What the screen shows |
| --- | --- |
| A barcode of a product in your store | Name, barcode, category, price and stock. **Confirm product**, then **Add to current sale** (this phone's sale only, with the normal POS stock checks). |
| A UPC-A barcode stored as EAN-13 (or the other way round), or a UPC-E | The same product, with a note that it is the same number in another form. |
| A barcode that no product in your store has | **Product not found**. The scanned code stays on screen, and nothing is created. You can take a photo, search manually, or scan again. |
| A code that cannot be a product (letters, not 8–14 digits) | **Not a product barcode**. QR codes are not read at all. |
| The server cannot be reached / a server error | A message, the code kept, and **Try again**. |
| Camera permission refused / no camera | An explanation, and **Try again**. |

**Photo.** The photo stays on the phone. It is not uploaded and not saved. You can retake it. **Identify by photo (Phase 2)** is shown, but disabled.

**POS on another computer.** The phone does not change another computer's sale; there is no cart sync in Phase 1. The screen shows the stored barcode in large digits and as a QR code. At the other computer, type the digits into the POS search box, or scan the QR code with a 2D scanner.

**Who sees it.** The screen appears only in the Android app, for Store Admin and Cashier. On Android it is their first page. System Admin never gets it, because System Admin has no store. Web and desktop are unchanged.

## Build requirements

These are the versions this project was set up with:

- Flutter 3.47 (Dart 3.x) and JDK 17.
- Android SDK with platform 36, build-tools 36 and platform-tools. NDK 28.2.13676358 is downloaded by Gradle if missing.
- `minSdk` is 24 (Android 7.0); `targetSdk` and `compileSdk` are 36.
- Android SDK licences must be accepted once, by you: `flutter doctor --android-licenses`.

The camera uses `mobile_scanner` 7.5 (CameraX, plus the bundled ML Kit barcode model) and `image_picker` 1.2.

The bundled ML Kit model adds a few MB to the APK, and it works without Google Play services downloads.

## Running the backend so the phone can reach it

The backend listens on port 8080 on all network interfaces.

### Android emulator

Use the default; nothing to set. Inside the emulator, `10.0.2.2` is the emulator's documented alias for the computer it runs on.

```bash
flutter run -d emulator-5554
```

### Physical phone

The phone and the computer must be on the same network. Find the computer's IP address: `ipconfig` on Windows, `ip addr` on Linux. Then:

```bash
flutter run -d <phone-id> --dart-define=API_BASE_URL=http://<PC-IP>:8080/api
```

- **Windows Firewall.** The first time the backend runs, allow Java on **Private** networks only. Or add an inbound rule for TCP 8080 on the Private profile. Do not open it on Public networks.
- No address is built into the app. `--dart-define=API_BASE_URL=...` always wins.

### Plain HTTP is for debug builds only

`android/app/src/debug/AndroidManifest.xml` sets `usesCleartextTraffic="true"`. This lets a debug build talk to `http://<PC-IP>:8080` on the store's local network.

The main and release manifests do not have it, so a **release build needs HTTPS** (Android's default). Do not copy that line into the main manifest.

Release signing is still Flutter's template, which signs with debug keys. That is fine for a demo APK, but not for publishing to a store.

## Real-device demo procedure

1. Start the backend on the computer, with a store that has products with real barcodes.
2. `flutter run` on the phone, using the `API_BASE_URL` of the computer. Log in as that store's cashier.
3. The camera screen opens. Allow the camera permission.
4. Scan a product's barcode. The real product appears. Tap **Confirm product**, then **Add to current sale**, then **Open the sale**. The POS shows the line.
5. Scan a barcode that is not in the store. You see **Product not found**, with the code kept. Take a photo, retake it, then go back.
6. Switch the language to Arabic and check the right-to-left layout.
7. Put the app in the background and bring it back. The camera resumes.

## Status of testing (be exact when reporting)

- **Tested:**
  - backend unit and API tests for the lookup;
  - Flutter widget tests with a fake camera and a mocked server (English and Arabic, small phones);
  - the web build and the full E2E suite (unchanged web app).
- **Not tested here:** an APK build and a physical Android device. The build environment had no accepted Android SDK licence, no emulator and no phone.

Run the procedure above on a real phone before claiming the Android app works on a device.
