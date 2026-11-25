import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:image_picker/image_picker.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

/// One code read by the live camera.
class ScannedCode {
  const ScannedCode(this.value, this.format);

  /// The decoded value, exactly as read (never changed here).
  final String value;

  /// EAN_13, EAN_8, UPC_A, UPC_E or CODE_128 (the backend's names).
  final String format;
}

/// Why the live camera is not showing.
enum CameraProblem { permissionDenied, unavailable, error }

/// The phone camera used by AI Product Identification (Phase 1): a live
/// preview that reads retail barcodes on the device (Google ML Kit through
/// mobile_scanner, no internet needed), and a product photo.
///
/// Only one camera session at a time: the scanner is stopped before the photo
/// camera opens, and started again afterwards by the screen.
abstract class ProductCamera {
  /// True where this camera works: the Android app (not web / desktop).
  static bool get supported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  Widget preview({
    required void Function(ScannedCode code) onCode,
    required Widget Function(BuildContext context, CameraProblem problem)
    onProblem,
  });

  Future<void> start();
  Future<void> stop();

  /// Opens the camera for a product photo; null when the user cancelled.
  Future<Uint8List?> takePhoto();

  /// Android may end the app while the camera app is open; this returns that
  /// photo when the app comes back, or null.
  Future<Uint8List?> recoverPhoto();

  Future<void> dispose();
}

/// Retail symbologies only: no QR codes or other 2D codes.
const _retailFormats = <BarcodeFormat>[
  BarcodeFormat.ean13,
  BarcodeFormat.ean8,
  BarcodeFormat.upcA,
  BarcodeFormat.upcE,
  BarcodeFormat.code128,
];

String? _formatName(BarcodeFormat f) => switch (f) {
  BarcodeFormat.ean13 => 'EAN_13',
  BarcodeFormat.ean8 => 'EAN_8',
  BarcodeFormat.upcA => 'UPC_A',
  BarcodeFormat.upcE => 'UPC_E',
  BarcodeFormat.code128 => 'CODE_128',
  _ => null,
};

/// The real camera: mobile_scanner 7 (CameraX + bundled ML Kit barcode model)
/// and image_picker (the phone's camera app) for photos.
class DeviceProductCamera implements ProductCamera {
  DeviceProductCamera()
    : _scanner = MobileScannerController(
        autoStart: false,
        formats: _retailFormats,
        // The same code is reported once until another code is seen; the
        // screen also ignores codes while a lookup is running.
        detectionSpeed: DetectionSpeed.noDuplicates,
      );

  final MobileScannerController _scanner;
  final ImagePicker _picker = ImagePicker();

  @override
  Widget preview({
    required void Function(ScannedCode code) onCode,
    required Widget Function(BuildContext context, CameraProblem problem)
    onProblem,
  }) => MobileScanner(
    controller: _scanner,
    onDetect: (capture) {
      for (final b in capture.barcodes) {
        final value = b.rawValue;
        final format = _formatName(b.format);
        if (value != null && value.isNotEmpty && format != null) {
          onCode(ScannedCode(value, format));
          return;
        }
      }
    },
    errorBuilder: (context, e) => onProblem(context, switch (e.errorCode) {
      MobileScannerErrorCode.permissionDenied => CameraProblem.permissionDenied,
      MobileScannerErrorCode.unsupported => CameraProblem.unavailable,
      _ => CameraProblem.error,
    }),
  );

  @override
  Future<void> start() => _scanner.start();

  @override
  Future<void> stop() => _scanner.stop();

  @override
  Future<Uint8List?> takePhoto() async {
    await _scanner.stop(); // one camera session at a time
    final file = await _picker.pickImage(
      source: ImageSource.camera,
      maxWidth: 1600,
      imageQuality: 85,
      requestFullMetadata: false,
    );
    return file?.readAsBytes();
  }

  @override
  Future<Uint8List?> recoverPhoto() async {
    final lost = await _picker.retrieveLostData();
    if (lost.isEmpty || lost.file == null) return null;
    return lost.file!.readAsBytes();
  }

  @override
  Future<void> dispose() => _scanner.dispose();
}
