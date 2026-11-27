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

## Barcode rules and limitations

**Inventory rule.** A product barcode is 8 to 14 ASCII digits, after trimming spaces. Any check digit is accepted. This is the rule in `Product.validate`, and it applies to the product form, the API and CSV import.

**Camera rule.** The camera lookup uses the same rule, so every barcode the inventory accepts can be found by the camera. Tests cover 8 to 14 digits, a wrong check digit and numeric Code 128.

**Symbologies read by the camera:**

- Read: EAN-13, EAN-8, UPC-A, UPC-E and Code 128. Code 128 is accepted only when its value is 8 to 14 digits.
- Not read: QR codes and other 2D codes.

**Limitations (no change made; a management decision is needed for each):**

- **Not read:**
  - ITF / ITF-14 (carton GTIN-14);
  - Code 39, Code 93 and Codabar.

  A product whose label uses one of these can still be sold by typing its digits in the POS search box.
- **Code 128 with letters** (for example `ABC-123`) cannot be a ClixSell product. The inventory never accepts letters, so the camera shows "Not a product barcode".
- **GS1-128 labels** (Code 128 with application identifiers such as `(01)` + GTIN + batch or date) are longer than 14 characters. They are shown as "Not a product barcode"; the GTIN inside is not extracted.

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

## Real-device test checklist (fill in on the phone; status: PENDING)

Record the phone model, Android version, and the result (PASS / FAIL + note) of each line. A simulated or test-rendered screenshot is **not** evidence for any line.

Before you start:
- Backend on the PC: `mvnw spring-boot:run` with the demo profile, on a disposable database, never the store's real one.
- App on the phone, built with `--dart-define=API_BASE_URL=http://<PC-IP>:8080/api`.
- Prepare two real products whose printed barcodes you add to **Shini** as Store Admin. Prepare one product whose barcode is in no store.

| # | Test | Expected |
| --- | --- | --- |
| 1 | Install (`flutter run` or `adb install app-debug.apk`) and launch | The app opens on the login screen; no crash. |
| 2 | Log in as `shini.cashier` | The camera screen is the first page; the top bar has a menu. |
| 3 | Camera permission: Deny, then Try again, then Allow | Deny shows the permission view (text whole, no line over the button). Allow shows the live picture with the white frame. |
| 4 | Live scan of a known EAN-13 / UPC product | It is read without pressing anything; one lookup only (hold it steady for 3 s, still one result). |
| 5 | Found in inventory | Real name, barcode, category, price and stock from the PC database. Confirm product, then Add to current sale, then Open the sale: the POS shows 1 line. |
| 6 | Same product again until its stock is reached | A stock-limit message; never more than the stock. |
| 7 | Unknown barcode | "Product not found", with the scanned digits kept; nothing created in Products. |
| 8 | A QR code / non-product code | The QR code is ignored; a letters-only Code 128 shows "Not a product barcode". |
| 9 | Capture photo, then Retake, then Back | Preview shows the new photo each time; Back returns with the barcode kept; the photo is not in the phone gallery or on the PC. |
| 10 | Network: stop the backend, scan | "Cannot reach the ClixSell server", with Try again; restart the backend, Try again, and the product is found. |
| 11 | Store isolation: log in as `neshan.cashier`, scan the Shini product | "Product not found" (another store's product is never shown). |
| 12 | Cashier POS rules | The cashier sees no cost price anywhere; the sale completes in POS with a receipt. |
| 13 | Background the app for 10 s, then return | The camera resumes; no frozen preview. |
| 14 | Arabic: switch the language | The whole screen is right-to-left and readable. |

## Status of testing (be exact when reporting)

- **Tested:**
  - backend unit and API tests for the lookup;
  - Flutter widget tests with a fake camera and a mocked server (English and Arabic, small phones);
  - the web build and the full E2E suite (unchanged web app).
- **Not tested here:** an APK build and a physical Android device. The build environment had no accepted Android SDK licence, no emulator and no phone. Every line of the checklist above is PENDING until the team runs it on a phone.

Run the procedure above on a real phone before claiming the Android app works on a device.
