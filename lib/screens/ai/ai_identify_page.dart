import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../l10n/ai_strings.dart';
import '../../models/product.dart';
import '../../services/barcode_lookup_client.dart';
import '../../state/pos_cart.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import 'product_camera.dart';

enum _Stage { scanning, lookingUp, found, notFound, invalid, error, photo }

/// AI Product Identification, Phase 1 (report Figure 3.10, mobile screen).
///
/// Barcode first, inventory first: the phone camera reads a retail barcode on
/// the device, the backend looks it up in the logged-in user's own store
/// (read only), and the real product is shown for the cashier to confirm.
/// When no product has the barcode, the code is kept and the cashier can take
/// a photo (kept on the phone, Phase 2 will identify it) or search manually.
///
/// No cloud AI is called in Phase 1. "Added to the current sale" is shown only
/// after the product really was added to this device's POS sale.
class AiIdentifyPage extends StatefulWidget {
  const AiIdentifyPage({
    super.key,
    required this.api,
    required this.createCamera,
    required this.canSell,
    required this.onOpenSale,
    required this.onSearchManually,
  });

  final BarcodeLookupApi api;

  /// Called once; the camera is owned (and disposed) by this page.
  final ProductCamera Function() createCamera;

  /// The user's role may sell on this device (POS).
  final bool canSell;
  final VoidCallback onOpenSale;
  final VoidCallback onSearchManually;

  @override
  State<AiIdentifyPage> createState() => _AiIdentifyPageState();
}

class _AiIdentifyPageState extends State<AiIdentifyPage>
    with WidgetsBindingObserver {
  _Stage _stage = _Stage.scanning;

  /// The code being / last looked up (kept for "not found" and Retry).
  ScannedCode? _code;
  BarcodeLookupResult? _result;
  bool _networkError = false;
  bool _confirmed = false;
  bool _addedToSale = false;
  String? _saleMessage;
  bool _saleMessageIsError = false;

  Uint8List? _photo;
  _Stage _photoReturn = _Stage.scanning;
  bool _pickingPhoto = false;
  int _lookupRequest = 0;

  late final ProductCamera _camera = widget.createCamera();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _startScanner();
    _recoverLostPhoto();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _lookupRequest++;
    unawaited(_camera.dispose().catchError((_) {}));
    super.dispose();
  }

  /// The camera is released while the app is in the background and opened
  /// again when it comes back (only while scanning, and not while the
  /// phone's camera app is taking a photo for us).
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (_pickingPhoto) return;
    switch (state) {
      case AppLifecycleState.resumed:
        if (_stage == _Stage.scanning) _startScanner();
      case AppLifecycleState.inactive:
      case AppLifecycleState.paused:
      case AppLifecycleState.hidden:
      case AppLifecycleState.detached:
        _stopScanner();
    }
  }

  void _startScanner() {
    // Errors (permission, no camera) are shown by the preview itself.
    unawaited(_camera.start().catchError((_) {}));
  }

  void _stopScanner() {
    unawaited(_camera.stop().catchError((_) {}));
  }

  Future<void> _recoverLostPhoto() async {
    try {
      final bytes = await _camera.recoverPhoto();
      if (bytes != null && mounted) _showPhoto(bytes, _Stage.scanning);
    } catch (_) {
      // nothing to recover
    }
  }

  // ── Scanning and lookup ──────────────────────────────────────────────────

  void _onCode(ScannedCode code) {
    // One lookup at a time: codes read while a result is open are ignored.
    if (_stage != _Stage.scanning || !mounted) return;
    _stopScanner();
    _lookup(code);
  }

  Future<void> _lookup(ScannedCode code) async {
    final req = ++_lookupRequest;
    setState(() {
      _stage = _Stage.lookingUp;
      _code = code;
      _result = null;
      _confirmed = false;
      _addedToSale = false;
      _saleMessage = null;
    });
    try {
      final result = await widget.api.lookup(code.value, format: code.format);
      if (!mounted || req != _lookupRequest) return;
      setState(() {
        _result = result;
        _stage = result.invalid
            ? _Stage.invalid
            : (result.found ? _Stage.found : _Stage.notFound);
      });
    } catch (e) {
      if (!mounted || req != _lookupRequest) return;
      setState(() {
        _networkError = e is ApiException && e.isNetworkError;
        _stage = _Stage.error;
      });
    }
  }

  void _scanAgain() {
    _lookupRequest++;
    setState(() {
      _stage = _Stage.scanning;
      _code = null;
      _result = null;
      _photo = null;
      _confirmed = false;
      _addedToSale = false;
      _saleMessage = null;
    });
    _startScanner();
  }

  // ── Product confirmed / added to the sale ────────────────────────────────

  void _addToSale(Product product) {
    final t = AiStrings.of(context);
    final cart = PosCartScope.read(context);
    final result = cart.add(product);
    setState(() {
      switch (result) {
        case AddResult.added:
          _addedToSale = true;
          _saleMessageIsError = false;
          _saleMessage = t.added(product.name);
        case AddResult.outOfStock:
          _saleMessageIsError = true;
          _saleMessage = t.outOfStockMsg(product.name);
        case AddResult.stockLimit:
          _saleMessageIsError = true;
          _saleMessage = t.stockLimit(product.name, product.quantity);
      }
    });
  }

  // ── Photo ────────────────────────────────────────────────────────────────

  Future<void> _takePhoto() async {
    final t = AiStrings.of(context);
    final returnTo = _stage == _Stage.photo ? _photoReturn : _stage;
    _pickingPhoto = true;
    Uint8List? bytes;
    var failed = false;
    try {
      bytes = await _camera.takePhoto();
    } catch (_) {
      failed = true;
    } finally {
      _pickingPhoto = false;
    }
    if (!mounted) return;
    if (bytes != null) {
      _showPhoto(bytes, returnTo);
      return;
    }
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(content: Text(failed ? t.photoFailed : t.photoCancelled)),
    );
    if (_stage == _Stage.scanning) _startScanner();
  }

  void _showPhoto(Uint8List bytes, _Stage returnTo) {
    _stopScanner();
    setState(() {
      _photo = bytes;
      _photoReturn = returnTo == _Stage.photo ? _Stage.scanning : returnTo;
      _stage = _Stage.photo;
    });
  }

  void _closePhoto() {
    final back = _photoReturn;
    setState(() {
      _photo = null;
      _stage = back;
    });
    if (back == _Stage.scanning) _startScanner();
  }

  // ── UI ───────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final t = AiStrings.of(context);
    return ColoredBox(
      color: AppColors.background,
      child: SafeArea(
        child: Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            // A short page: everything is built, so every button stays
            // reachable (and findable) on the smallest phones.
            child: SingleChildScrollView(
              key: const Key('aiIdentifyPage'),
              padding: const EdgeInsets.all(AppSpacing.lg),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    t.title,
                    style: const TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                      color: AppColors.textPrimary,
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xs),
                  Text(
                    t.subtitle,
                    style: const TextStyle(color: AppColors.textSecondary),
                  ),
                  const SizedBox(height: AppSpacing.lg),
                  ..._body(t),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  List<Widget> _body(AiStrings t) => switch (_stage) {
    _Stage.scanning || _Stage.lookingUp => _scanningBody(t),
    _Stage.found => _foundBody(t, _result!),
    _Stage.notFound => _notFoundBody(t),
    _Stage.invalid => _invalidBody(t),
    _Stage.error => _errorBody(t),
    _Stage.photo => _photoBody(t),
  };

  List<Widget> _scanningBody(AiStrings t) {
    final looking = _stage == _Stage.lookingUp;
    return [
      ClipRRect(
        borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
        child: AspectRatio(
          aspectRatio: 3 / 4,
          child: Stack(
            fit: StackFit.expand,
            children: [
              const ColoredBox(color: Colors.black),
              KeyedSubtree(
                key: const Key('aiPreview'),
                child: _camera.preview(
                  onCode: _onCode,
                  onProblem: (context, problem) => _CameraProblemView(
                    problem: problem,
                    onRetry: _startScanner,
                  ),
                  // Only over the live picture: never over the camera
                  // problem view (it used to cut through its text / button).
                  overlay: looking
                      ? null
                      : const IgnorePointer(child: _ScanFrame()),
                ),
              ),
              if (looking)
                ColoredBox(
                  key: const Key('aiLookingUp'),
                  color: Colors.black54,
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const CircularProgressIndicator(color: Colors.white),
                        const SizedBox(height: AppSpacing.md),
                        Text(
                          t.lookingUp,
                          style: const TextStyle(color: Colors.white),
                        ),
                        if (_code != null)
                          Text(
                            t.scanned(_code!.value),
                            style: const TextStyle(color: Colors.white70),
                          ),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
      const SizedBox(height: AppSpacing.md),
      Text(
        t.scanHint,
        textAlign: TextAlign.center,
        style: const TextStyle(color: AppColors.textSecondary),
      ),
      const SizedBox(height: AppSpacing.lg),
      _actions([
        OutlinedButton.icon(
          key: const Key('aiCapturePhoto'),
          onPressed: looking ? null : _takePhoto,
          icon: const Icon(Icons.photo_camera_outlined),
          label: Text(t.capturePhoto),
        ),
        OutlinedButton.icon(
          key: const Key('aiSearchManually'),
          onPressed: looking ? null : widget.onSearchManually,
          icon: const Icon(Icons.search_rounded),
          label: Text(t.searchManually),
        ),
      ]),
    ];
  }

  List<Widget> _foundBody(AiStrings t, BarcodeLookupResult r) {
    final p = r.product!;
    final arabic = t.isArabic;
    final out = p.quantity <= 0;
    final stored = r.matchedBarcode!;
    return [
      _Card(
        key: const Key('aiFound'),
        children: [
          Row(
            children: [
              const Icon(Icons.verified_rounded, color: AppColors.success),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(
                  t.resultTitle,
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    color: AppColors.success,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.xs),
          Text(
            t.sourceBarcode,
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 12,
            ),
          ),
          const Divider(height: AppSpacing.xl),
          Text(
            p.name,
            key: const Key('aiProductName'),
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: AppSpacing.md),
          _row(t.barcode, stored, valueKey: const Key('aiProductBarcode')),
          _row(t.category, p.category.label(arabic: arabic)),
          _row(t.price, Formatters.currency(p.price)),
          _row(
            t.stock,
            out ? t.outOfStock : '${p.quantity}',
            valueColor: out ? AppColors.danger : null,
          ),
          if (r.matchType == BarcodeMatchType.gtinEquivalent) ...[
            const SizedBox(height: AppSpacing.sm),
            _note(t.sameNumber(r.scannedCode, stored), AppColors.info),
          ],
        ],
      ),
      const SizedBox(height: AppSpacing.lg),
      if (!_confirmed)
        _actions([
          FilledButton.icon(
            key: const Key('aiConfirm'),
            onPressed: () => setState(() => _confirmed = true),
            icon: const Icon(Icons.check_rounded),
            label: Text(t.confirmProduct),
          ),
          OutlinedButton(
            key: const Key('aiScanAgain'),
            onPressed: _scanAgain,
            child: Text(t.scanAgain),
          ),
        ])
      else ...[
        _note(t.confirmed, AppColors.success, key: const Key('aiConfirmed')),
        const SizedBox(height: AppSpacing.md),
        if (widget.canSell) ...[
          if (_saleMessage != null) ...[
            _note(
              _saleMessage!,
              _saleMessageIsError ? AppColors.danger : AppColors.success,
              key: const Key('aiSaleMessage'),
            ),
            const SizedBox(height: AppSpacing.md),
          ],
          _actions([
            FilledButton.icon(
              key: const Key('aiAddToSale'),
              onPressed: () => _addToSale(p),
              icon: const Icon(Icons.add_shopping_cart_rounded),
              label: Text(t.addToSale),
            ),
            if (_addedToSale)
              OutlinedButton.icon(
                key: const Key('aiOpenSale'),
                onPressed: widget.onOpenSale,
                icon: const Icon(Icons.point_of_sale_rounded),
                label: Text(t.openSale),
              ),
          ]),
          const SizedBox(height: AppSpacing.lg),
        ],
        _OtherDevice(barcode: stored, text: t.otherDevice),
        const SizedBox(height: AppSpacing.lg),
        _actions([
          OutlinedButton(
            key: const Key('aiScanAgain'),
            onPressed: _scanAgain,
            child: Text(t.scanAgain),
          ),
        ]),
      ],
    ];
  }

  List<Widget> _notFoundBody(AiStrings t) => [
    _Card(
      key: const Key('aiNotFound'),
      children: [
        Row(
          children: [
            const Icon(Icons.search_off_rounded, color: AppColors.warning),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Text(
                t.notFoundTitle,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        _scannedCode(t),
        const SizedBox(height: AppSpacing.md),
        Text(t.notFoundBody),
        const SizedBox(height: AppSpacing.md),
        _note(t.phase2, AppColors.info),
      ],
    ),
    const SizedBox(height: AppSpacing.lg),
    _actions([
      FilledButton.icon(
        key: const Key('aiCapturePhoto'),
        onPressed: _takePhoto,
        icon: const Icon(Icons.photo_camera_outlined),
        label: Text(t.capturePhoto),
      ),
      OutlinedButton.icon(
        key: const Key('aiSearchManually'),
        onPressed: widget.onSearchManually,
        icon: const Icon(Icons.search_rounded),
        label: Text(t.searchManually),
      ),
      OutlinedButton(
        key: const Key('aiScanAgain'),
        onPressed: _scanAgain,
        child: Text(t.scanAgain),
      ),
      _identifyByPhoto(t),
    ]),
  ];

  List<Widget> _invalidBody(AiStrings t) => [
    _Card(
      key: const Key('aiInvalid'),
      children: [
        Row(
          children: [
            const Icon(Icons.qr_code_2_rounded, color: AppColors.warning),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Text(
                t.invalidTitle,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        _scannedCode(t),
        const SizedBox(height: AppSpacing.md),
        Text(t.invalidBody),
      ],
    ),
    const SizedBox(height: AppSpacing.lg),
    _actions([
      FilledButton(
        key: const Key('aiScanAgain'),
        onPressed: _scanAgain,
        child: Text(t.scanAgain),
      ),
      OutlinedButton.icon(
        key: const Key('aiSearchManually'),
        onPressed: widget.onSearchManually,
        icon: const Icon(Icons.search_rounded),
        label: Text(t.searchManually),
      ),
    ]),
  ];

  List<Widget> _errorBody(AiStrings t) => [
    _Card(
      key: const Key('aiError'),
      children: [
        Row(
          children: [
            const Icon(Icons.cloud_off_rounded, color: AppColors.danger),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Text(_networkError ? t.networkError : t.serverError),
            ),
          ],
        ),
        if (_code != null) ...[
          const SizedBox(height: AppSpacing.md),
          _scannedCode(t),
        ],
      ],
    ),
    const SizedBox(height: AppSpacing.lg),
    _actions([
      FilledButton.icon(
        key: const Key('aiRetry'),
        onPressed: _code == null ? null : () => _lookup(_code!),
        icon: const Icon(Icons.refresh_rounded),
        label: Text(t.retry),
      ),
      OutlinedButton(
        key: const Key('aiScanAgain'),
        onPressed: _scanAgain,
        child: Text(t.scanAgain),
      ),
    ]),
  ];

  List<Widget> _photoBody(AiStrings t) => [
    _Card(
      children: [
        Text(t.photoTitle, style: const TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: AppSpacing.md),
        ClipRRect(
          borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
          child: Image.memory(
            _photo!,
            key: const Key('aiPhotoPreview'),
            fit: BoxFit.contain,
            height: 320,
            gaplessPlayback: true,
            errorBuilder: (_, _, _) => const SizedBox(
              height: 120,
              child: Center(child: Icon(Icons.broken_image_outlined)),
            ),
          ),
        ),
        const SizedBox(height: AppSpacing.md),
        if (_code != null && _photoReturn == _Stage.notFound) ...[
          _scannedCode(t),
          const SizedBox(height: AppSpacing.sm),
        ],
        Text(
          t.photoNotSaved,
          style: const TextStyle(color: AppColors.textSecondary, fontSize: 12),
        ),
        const SizedBox(height: AppSpacing.sm),
        _note(t.phase2, AppColors.info),
      ],
    ),
    const SizedBox(height: AppSpacing.lg),
    _actions([
      FilledButton.icon(
        key: const Key('aiRetake'),
        onPressed: _takePhoto,
        icon: const Icon(Icons.replay_rounded),
        label: Text(t.retakePhoto),
      ),
      OutlinedButton.icon(
        key: const Key('aiBack'),
        onPressed: _closePhoto,
        icon: const Icon(Icons.arrow_back_rounded),
        label: Text(t.back),
      ),
      OutlinedButton.icon(
        key: const Key('aiSearchManually'),
        onPressed: widget.onSearchManually,
        icon: const Icon(Icons.search_rounded),
        label: Text(t.searchManually),
      ),
      _identifyByPhoto(t),
    ]),
  ];

  /// Phase 2 (cloud AI) is not authorised yet: shown, but disabled.
  Widget _identifyByPhoto(AiStrings t) => OutlinedButton.icon(
    key: const Key('aiIdentifyByPhoto'),
    onPressed: null,
    icon: const Icon(Icons.auto_awesome_outlined),
    label: Text(t.identifyByPhoto),
  );

  Widget _scannedCode(AiStrings t) => Container(
    padding: const EdgeInsets.all(AppSpacing.md),
    decoration: BoxDecoration(
      color: AppColors.background,
      borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
      border: Border.all(color: AppColors.border),
    ),
    child: Text(
      t.scanned(_code?.value ?? ''),
      key: const Key('aiScannedCode'),
      style: const TextStyle(fontWeight: FontWeight.w600),
    ),
  );

  Widget _row(String label, String value, {Key? valueKey, Color? valueColor}) =>
      Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 110,
              child: Text(
                label,
                style: const TextStyle(color: AppColors.textSecondary),
              ),
            ),
            Expanded(
              child: Text(
                value,
                key: valueKey,
                style: TextStyle(
                  fontWeight: FontWeight.w600,
                  color: valueColor ?? AppColors.textPrimary,
                ),
              ),
            ),
          ],
        ),
      );

  Widget _note(String text, Color color, {Key? key}) => Container(
    key: key,
    padding: const EdgeInsets.all(AppSpacing.md),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.08),
      borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
      border: Border.all(color: color.withValues(alpha: 0.35)),
    ),
    child: Text(text, style: TextStyle(color: color)),
  );

  Widget _actions(List<Widget> buttons) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      for (final b in buttons)
        Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.sm),
          child: b,
        ),
    ],
  );
}

class _Card extends StatelessWidget {
  const _Card({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(AppSpacing.lg),
    decoration: BoxDecoration(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
      border: Border.all(color: AppColors.border),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: children,
    ),
  );
}

/// A separate computer's POS cannot be changed from the phone (no cart sync
/// in Phase 1): the stored barcode is shown large, plus a QR code of the same
/// digits for a 2D scanner.
class _OtherDevice extends StatelessWidget {
  const _OtherDevice({required this.barcode, required this.text});

  final String barcode;
  final String text;

  @override
  Widget build(BuildContext context) => _Card(
    key: const Key('aiOtherDevice'),
    children: [
      Text(text, style: const TextStyle(color: AppColors.textSecondary)),
      const SizedBox(height: AppSpacing.md),
      Directionality(
        textDirection: TextDirection.ltr,
        child: SelectableText(
          barcode,
          key: const Key('aiBarcodeDigits'),
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontSize: 26,
            fontWeight: FontWeight.w700,
            letterSpacing: 2,
          ),
        ),
      ),
      const SizedBox(height: AppSpacing.md),
      Center(
        child: Container(
          color: Colors.white,
          padding: const EdgeInsets.all(AppSpacing.sm),
          child: QrImageView(
            key: const Key('aiBarcodeQr'),
            data: barcode,
            size: 150,
            backgroundColor: Colors.white,
          ),
        ),
      ),
    ],
  );
}

class _ScanFrame extends StatelessWidget {
  const _ScanFrame();

  @override
  Widget build(BuildContext context) => Center(
    key: const Key('aiScanFrame'),
    child: FractionallySizedBox(
      widthFactor: 0.8,
      heightFactor: 0.3,
      child: DecoratedBox(
        decoration: BoxDecoration(
          border: Border.all(color: Colors.white, width: 3),
          borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        ),
      ),
    ),
  );
}

class _CameraProblemView extends StatelessWidget {
  const _CameraProblemView({required this.problem, required this.onRetry});

  final CameraProblem problem;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final t = AiStrings.of(context);
    final (title, body, icon) = switch (problem) {
      CameraProblem.permissionDenied => (
        t.permissionTitle,
        t.permissionBody,
        Icons.no_photography_outlined,
      ),
      CameraProblem.unavailable => (
        t.unavailableTitle,
        t.unavailableBody,
        Icons.videocam_off_outlined,
      ),
      CameraProblem.error => (
        t.cameraErrorTitle,
        t.unavailableBody,
        Icons.error_outline_rounded,
      ),
    };
    return ColoredBox(
      key: Key('aiCameraProblem-${problem.name}'),
      color: AppColors.surface,
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 48, color: AppColors.textMuted),
              const SizedBox(height: AppSpacing.md),
              Text(
                title,
                textAlign: TextAlign.center,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: AppSpacing.sm),
              Text(body, textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.lg),
              FilledButton(
                key: const Key('aiCameraRetry'),
                onPressed: onRetry,
                child: Text(t.retry),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
