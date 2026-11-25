import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../l10n/product_file_strings.dart';
import '../../models/product_file.dart';
import '../../services/product_file_client.dart';
import '../../theme/app_theme.dart';

/// Import products from a CSV file (UR-M11, Figure 3.3): shows the server's
/// check of every row, then — only after an explicit confirmation — creates all
/// products in one transaction, or none. Nothing is saved before "Import".
class ProductImportDialog extends StatefulWidget {
  const ProductImportDialog({
    super.key,
    required this.api,
    required this.fileName,
    required this.bytes,
    required this.preview,
    required this.isImported,
  });

  final ProductFileApi api;
  final String fileName;
  final Uint8List bytes;
  final ImportPreview preview;

  /// After an unknown outcome (no answer in time): reloads the store and says
  /// whether all these barcodes exist now.
  final Future<bool> Function(List<String> barcodes) isImported;

  @override
  State<ProductImportDialog> createState() => _ProductImportDialogState();
}

class _ProductImportDialogState extends State<ProductImportDialog> {
  late ImportPreview _preview = widget.preview;
  bool _confirmed = false;
  bool _busy = false;
  bool _sent = false; // one import per dialog: a second click never sends again
  ImportResult? _result;
  String? _message;
  bool _messageIsError = false;

  bool get _canImport =>
      _preview.canImport && _confirmed && !_busy && !_sent && _result == null;

  Future<void> _import() async {
    // A second click that arrives before the button is rebuilt as disabled
    // must not send the file again.
    if (_busy || _sent || _result != null) return;
    final t = ProductFileStrings.of(context);
    setState(() {
      _busy = true;
      _sent = true;
      _message = null;
    });
    try {
      final r = await widget.api.importFile(
        widget.fileName,
        widget.bytes,
        _preview.fileHash,
      );
      if (!mounted) return;
      setState(() => _result = r);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.isNetworkError || (e.statusCode ?? 0) >= 500) {
        // Unknown outcome: check the store before saying anything.
        final barcodes = [
          for (final r in _preview.rows) r.values['barcode'] ?? '',
        ];
        final done = await widget.isImported(barcodes);
        if (!mounted) return;
        setState(() {
          _messageIsError = !done;
          _message = done
              ? t.unknownOutcomeImported
              : t.unknownOutcomeNotImported;
          if (done) {
            _result = ImportResult(
              created: barcodes.length,
              suppliersCreated: _preview.newSuppliers,
            );
          } else {
            _sent = false; // checked: nothing saved, a new attempt is safe
          }
        });
        return;
      }
      final details = e.detailsMap;
      setState(() {
        _messageIsError = true;
        if (details.containsKey('rows')) {
          _preview = ImportPreview.fromJson(details);
        }
        _message = switch (e.code) {
          'IMPORT_FILE_CHANGED' => t.fileChanged,
          'IMPORT_INVALID' => t.fixAndRetry,
          _ => t.importFailed,
        };
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = ProductFileStrings.of(context);
    final p = _preview;
    final result = _result;
    return AlertDialog(
      key: const Key('importDialog'),
      title: Row(
        children: [
          Icon(
            result != null
                ? Icons.check_circle_rounded
                : Icons.upload_file_rounded,
            color: result != null ? AppColors.success : AppColors.primary,
          ),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Text(result != null ? t.successTitle : t.previewTitle),
          ),
        ],
      ),
      content: SizedBox(
        width: 980,
        child: result != null ? _resultView(t, result) : _previewView(t, p),
      ),
      actions: result != null
          ? [
              FilledButton(
                key: const Key('importCloseButton'),
                onPressed: () => Navigator.of(context).pop(result),
                child: Text(t.close),
              ),
            ]
          : [
              TextButton(
                key: const Key('importCancelButton'),
                onPressed: _busy ? null : () => Navigator.of(context).pop(),
                child: Text(t.cancel),
              ),
              FilledButton.icon(
                key: const Key('importConfirmButton'),
                onPressed: _canImport ? _import : null,
                icon: _busy
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Icon(Icons.file_download_done_rounded, size: 18),
                label: Text(
                  _busy
                      ? t.importing
                      : (p.canImport
                            ? t.importNow(p.validRows)
                            : t.importDisabled),
                ),
              ),
            ],
    );
  }

  Widget _resultView(ProductFileStrings t, ImportResult r) => Column(
    key: const Key('importResult'),
    mainAxisSize: MainAxisSize.min,
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      if (_message != null) ...[
        _notice(_message!, error: false),
        const SizedBox(height: AppSpacing.md),
      ],
      Text(
        t.created(r.created),
        style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
      ),
      if (r.suppliersCreated.isNotEmpty) ...[
        const SizedBox(height: AppSpacing.sm),
        Text(t.suppliersCreated(r.suppliersCreated)),
      ],
    ],
  );

  Widget _previewView(ProductFileStrings t, ImportPreview p) {
    final rows = [...p.rows]
      ..sort((a, b) {
        if (a.isValid != b.isValid) return a.isValid ? 1 : -1;
        return a.line.compareTo(b.line);
      });
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(p.fileName, style: const TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: AppSpacing.xs),
        Text(
          t.nothingSaved,
          style: const TextStyle(color: AppColors.textSecondary),
        ),
        const SizedBox(height: AppSpacing.md),
        if (_message != null) ...[
          _notice(_message!, error: _messageIsError),
          const SizedBox(height: AppSpacing.md),
        ],
        for (final f in p.fileErrors) ...[
          _notice(t.fileError(f), error: true, key: Key('importFileError-$f')),
          const SizedBox(height: AppSpacing.sm),
        ],
        if (p.fileErrors.isEmpty) ...[
          Text(
            t.rowsSummary(p.totalRows, p.validRows, p.invalidRows),
            key: const Key('importSummary'),
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          if (p.newSuppliers.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.xs),
            Text(
              t.newSuppliers(p.newSuppliers),
              key: const Key('importNewSuppliers'),
            ),
          ],
          const SizedBox(height: AppSpacing.sm),
          _notice(
            '${t.onlyNew} ${t.allOrNothing}',
            error: false,
            key: const Key('importRules'),
          ),
          if (!p.canImport) ...[
            const SizedBox(height: AppSpacing.sm),
            _notice(
              t.fixAndRetry,
              error: true,
              key: const Key('importFixHint'),
            ),
          ],
          const SizedBox(height: AppSpacing.md),
          _table(t, rows),
          if (p.canImport) ...[
            const SizedBox(height: AppSpacing.md),
            CheckboxListTile(
              key: const Key('importConfirmBox'),
              value: _confirmed,
              onChanged: _busy || _sent
                  ? null
                  : (v) => setState(() => _confirmed = v ?? false),
              controlAffinity: ListTileControlAffinity.leading,
              contentPadding: EdgeInsets.zero,
              title: Text(t.confirmBox(p.validRows)),
            ),
          ],
        ],
      ],
    );
  }

  static const _cols = [
    'name',
    'barcode',
    'category',
    'sellingPrice',
    'costPrice',
    'quantity',
    'minStock',
    'supplier',
  ];
  static const _flex = [5, 4, 3, 2, 2, 2, 2, 4];

  Widget _table(ProductFileStrings t, List<ImportRow> rows) {
    const head = TextStyle(
      fontSize: 12,
      fontWeight: FontWeight.w700,
      color: AppColors.textSecondary,
    );
    return Container(
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.border),
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
      ),
      child: Column(
        children: [
          Container(
            color: AppColors.background,
            padding: const EdgeInsets.symmetric(
              horizontal: AppSpacing.md,
              vertical: AppSpacing.sm,
            ),
            child: Row(
              children: [
                SizedBox(width: 48, child: Text(t.line, style: head)),
                for (var i = 0; i < _cols.length; i++)
                  Expanded(
                    flex: _flex[i],
                    child: Text(t.column(_cols[i]), style: head),
                  ),
              ],
            ),
          ),
          SizedBox(
            height: 320,
            child: ListView.separated(
              key: const Key('importRows'),
              itemCount: rows.length,
              separatorBuilder: (_, _) =>
                  const Divider(height: 1, color: AppColors.border),
              itemBuilder: (context, i) => _rowView(t, rows[i]),
            ),
          ),
        ],
      ),
    );
  }

  Widget _rowView(ProductFileStrings t, ImportRow r) {
    final bad = !r.isValid;
    return Container(
      key: Key('importRow-${r.line}'),
      color: bad ? AppColors.dangerSoft : null,
      padding: const EdgeInsets.symmetric(
        horizontal: AppSpacing.md,
        vertical: AppSpacing.sm,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                width: 48,
                child: Text(
                  '${r.line}',
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    color: bad ? AppColors.danger : AppColors.textSecondary,
                  ),
                ),
              ),
              for (var i = 0; i < _cols.length; i++)
                Expanded(
                  flex: _flex[i],
                  child: Text(
                    // Each value in its own direction (FSI): "-3" stays "-3" in
                    // Arabic, an Arabic name stays right-to-left in English.
                    _cols[i] == 'supplier' &&
                            t.supplierStatus(r.supplierStatus).isNotEmpty
                        ? '${t.iso(r.values['supplier'] ?? '')} (${t.supplierStatus(r.supplierStatus)})'
                        : t.iso(r.values[_cols[i]] ?? ''),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: r.errors.containsKey(_cols[i])
                          ? AppColors.danger
                          : AppColors.textPrimary,
                      fontWeight: r.errors.containsKey(_cols[i])
                          ? FontWeight.w700
                          : FontWeight.w400,
                    ),
                  ),
                ),
            ],
          ),
          if (bad)
            Padding(
              padding: const EdgeInsetsDirectional.only(start: 48, top: 4),
              child: Text(
                [
                  for (final e in r.errors.entries)
                    '${t.column(e.key)}: ${t.rowError(e.key, e.value)}',
                ].join(' · '),
                key: Key('importRowErrors-${r.line}'),
                style: const TextStyle(fontSize: 12.5, color: AppColors.danger),
              ),
            ),
        ],
      ),
    );
  }

  Widget _notice(String text, {required bool error, Key? key}) => Container(
    key: key,
    padding: const EdgeInsets.all(AppSpacing.md),
    decoration: BoxDecoration(
      color: error ? AppColors.dangerSoft : AppColors.infoSoft,
      borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
    ),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(
          error ? Icons.error_outline_rounded : Icons.info_outline_rounded,
          size: 18,
          color: error ? AppColors.danger : AppColors.info,
        ),
        const SizedBox(width: AppSpacing.sm),
        Expanded(child: Text(text)),
      ],
    ),
  );
}
