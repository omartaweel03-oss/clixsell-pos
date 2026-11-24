import 'package:flutter/material.dart';

import '../../l10n/purchase_order_strings.dart';
import '../../models/purchase_order.dart';
import '../../models/supplier.dart';
import '../../services/api_http.dart';
import '../../services/purchase_api_client.dart';
import '../../services/supplier_api_client.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/page_header.dart';
import 'purchase_recommendations_page.dart';
import 'purchase_widgets.dart';

/// Screen 3 — create a purchase order, or edit one that is PENDING_APPROVAL.
///
/// Idempotency: one clientRequestId per form (one logical new order). If the
/// outcome of saving is unknown (no answer, timeout, busy database, server
/// error), the exact request is kept, the form is locked, and "Check and
/// retry" resends exactly that request: the server returns the order if it
/// was already created, so a second order can never be created by accident.
class PurchaseOrderFormPage extends StatefulWidget {
  const PurchaseOrderFormPage({
    super.key,
    required this.api,
    required this.supplierApi,
    required this.onSaved,
    required this.onCancel,
    this.seed,
    this.editOrderId,
  });

  final PurchaseApi api;
  final SupplierApi supplierApi;
  final ValueChanged<PurchaseOrder> onSaved;
  final VoidCallback onCancel;
  final PurchaseOrderSeed? seed;
  final int? editOrderId;

  @override
  State<PurchaseOrderFormPage> createState() => PurchaseOrderFormPageState();
}

class _Line {
  _Line(
    this.productId,
    this.name,
    this.barcode,
    int quantity, {
    this.suggested,
    this.cost,
  }) : qty = TextEditingController(text: '$quantity');

  final int productId;
  final String name;
  final String barcode;
  final int? suggested;
  final double? cost;
  final TextEditingController qty;
}

class PurchaseOrderFormPageState extends State<PurchaseOrderFormPage> {
  // one logical creation request per form instance
  final String _clientRequestId = newClientId();

  List<Supplier> _suppliers = const [];
  int? _supplierId;
  String _source = 'MANUAL';
  PurchaseCandidates? _candidates;
  Map<int, RecommendationItem> _recs = const {};
  PurchaseOrder? _editing;
  final List<_Line> _lines = [];
  final List<String> _deletedLines = [];
  final _note = TextEditingController();
  final Set<CautionAck> _acks = {};

  /// Cautions the server asked to acknowledge (409), by product.
  final Map<int, List<PurchaseCaution>> _serverCautions = {};

  bool _loading = true;
  bool _loadingProducts = false;
  bool _saving = false;
  bool _dirty = false;
  bool _saved = false;
  ApiException? _loadError;
  ApiException? _saveError;

  /// A create request whose outcome is unknown: the form is locked to it.
  CreateOrderRequest? _unresolved;
  String? _info;

  bool get _editMode => widget.editOrderId != null;
  bool get _locked => _unresolved != null || _saving;

  @override
  void initState() {
    super.initState();
    _note.addListener(_touch);
    _init();
  }

  @override
  void dispose() {
    _note.dispose();
    for (final l in _lines) {
      l.qty.dispose();
    }
    super.dispose();
  }

  void _touch() {
    if (!_dirty && !_loading) setState(() => _dirty = true);
  }

  /// Unsaved changes → ask before leaving (also asked while an outcome is unknown).
  Future<bool> confirmLeave() async {
    if (_saved || (!_dirty && _unresolved == null)) return true;
    final t = PurchaseOrderStrings.of(context);
    return confirmAction(
      context,
      title: t.leaveTitle,
      body: _unresolved != null ? t.unresolvedBody : t.leaveBody,
      confirmLabel: t.discard,
      cancelLabel: t.keepEditing,
      color: AppColors.danger,
    );
  }

  Future<void> _init() async {
    setState(() {
      _loading = true;
      _loadError = null;
    });
    try {
      final suppliers = await widget.supplierApi.list();
      PurchaseOrder? editing;
      if (_editMode) editing = await widget.api.get(widget.editOrderId!);
      if (!mounted) return;
      _suppliers = suppliers
        ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
      if (editing != null) {
        _editing = editing;
        _supplierId = editing.supplierId;
        _source = editing.source;
        _note.text = editing.note ?? '';
        for (final i in editing.items) {
          if (i.productId == null) {
            _deletedLines.add(i.productName);
          } else {
            _lines.add(
              _Line(
                i.productId!,
                i.productName,
                i.barcode,
                i.quantity,
                suggested: i.suggestedQuantity,
                cost: i.expectedUnitCost,
              ),
            );
          }
        }
      } else if (widget.seed != null) {
        _supplierId = widget.seed!.supplierId;
        _source = widget.seed!.source;
      }
      if (_supplierId != null) await _loadProducts(seed: widget.seed);
      if (!mounted) return;
      setState(() => _loading = false);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loadError = e;
        _loading = false;
      });
    }
  }

  Future<void> _loadProducts({PurchaseOrderSeed? seed}) async {
    setState(() => _loadingProducts = true);
    try {
      final c = await widget.api.candidates(_supplierId!);
      var recs = <int, RecommendationItem>{};
      if (_source == 'GUARDIAN') {
        final r = await widget.api.recommendations();
        for (final s in r.suppliers.where((s) => s.supplierId == _supplierId)) {
          for (final i in s.items) {
            recs[i.productId] = i;
          }
        }
      }
      if (!mounted) return;
      setState(() {
        _candidates = c;
        _recs = recs;
        if (seed != null) {
          for (final l in seed.lines) {
            final p = c.products
                .where((x) => x.productId == l.productId)
                .firstOrNull;
            if (p == null) continue;
            _lines.add(
              _Line(
                p.productId,
                p.name,
                p.barcode,
                l.quantity,
                suggested: recs[p.productId]?.suggestedQuantity,
                cost: p.expectedUnitCost,
              ),
            );
          }
        }
        for (final l in _lines) {
          l.qty.removeListener(_touch);
          l.qty.addListener(_touch);
        }
        _loadingProducts = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loadError = e;
        _loadingProducts = false;
      });
    }
  }

  void _chooseSupplier(int? id) {
    if (id == null || id == _supplierId) return;
    setState(() {
      _supplierId = id;
      for (final l in _lines) {
        l.qty.dispose();
      }
      _lines.clear();
      _acks.clear();
      _serverCautions.clear();
      _candidates = null;
      _dirty = true;
    });
    _loadProducts();
  }

  void _add(PurchaseCandidate p) {
    if (_lines.any((l) => l.productId == p.productId)) return;
    final suggested = _recs[p.productId]?.suggestedQuantity;
    final line = _Line(
      p.productId,
      p.name,
      p.barcode,
      suggested ?? 1,
      suggested: suggested,
      cost: p.expectedUnitCost,
    );
    line.qty.addListener(_touch);
    setState(() {
      _lines.add(line);
      _dirty = true;
    });
  }

  void _remove(_Line l) {
    setState(() {
      _lines.remove(l);
      _acks.removeWhere((a) => a.productId == l.productId);
      _dirty = true;
    });
    l.qty.dispose();
  }

  static int? _qty(String text) {
    final v = int.tryParse(text.trim());
    return v == null || v < 1 || v > 100000 ? null : v;
  }

  /// Every caution of the chosen products that still needs a tick.
  List<(int, String, PurchaseCaution)> get _cautions {
    final out = <(int, String, PurchaseCaution)>[];
    for (final l in _lines) {
      final fromCandidates =
          _candidates?.products
              .where((p) => p.productId == l.productId)
              .firstOrNull
              ?.cautions ??
          const [];
      final seen = <int>{};
      for (final c in [...fromCandidates, ...?_serverCautions[l.productId]]) {
        if (seen.add(c.orderId)) out.add((l.productId, l.name, c));
      }
    }
    return out;
  }

  bool get _valid =>
      _supplierId != null &&
      _lines.isNotEmpty &&
      _lines.every((l) => _qty(l.qty.text) != null) &&
      _note.text.trim().runes.length <= 500 &&
      _cautions.every((c) => _acks.contains(CautionAck(c.$1, c.$3.orderId)));

  List<OrderLine> get _orderLines => [
    for (final l in _lines) OrderLine(l.productId, _qty(l.qty.text)!),
  ];

  String? get _noteValue =>
      _note.text.trim().isEmpty ? null : _note.text.trim();

  List<CautionAck> get _ackList => [
    for (final c in _cautions)
      if (_acks.contains(CautionAck(c.$1, c.$3.orderId)))
        CautionAck(c.$1, c.$3.orderId),
  ];

  /// An outcome we cannot know from the answer.
  static bool _unknownOutcome(ApiException e) =>
      e.isNetworkError ||
      e.code == 'DATABASE_BUSY' ||
      (e.statusCode != null && e.statusCode! >= 500);

  Future<void> _save() async {
    if (!_valid || _saving) return;
    if (_editMode) return _saveEdit();
    final request =
        _unresolved ??
        CreateOrderRequest(
          clientRequestId: _clientRequestId,
          supplierId: _supplierId!,
          source: _source,
          note: _noteValue,
          lines: _orderLines,
          acks: _ackList,
        );
    await _send(request);
  }

  /// Sends a create request; on an unknown outcome keeps it unchanged.
  Future<void> _send(CreateOrderRequest request) async {
    setState(() {
      _saving = true;
      _saveError = null;
      _info = null;
    });
    try {
      final order = await widget.api.create(request);
      if (!mounted) return;
      _saved = true;
      setState(() {
        _saving = false;
        _unresolved = null;
      });
      widget.onSaved(order);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _saveError = e;
        if (_unknownOutcome(e)) {
          _unresolved =
              request; // locked: only this exact request may be resent
        } else {
          _unresolved = null; // definitively refused: nothing was created
          _absorbDetails(e);
        }
      });
    }
  }

  void _absorbDetails(ApiException e) {
    if (e.code == 'REORDER_CAUTION_ACK_REQUIRED') {
      for (final c
          in (e.detailsMap['cautions'] as List<dynamic>? ?? const [])) {
        final caution = PurchaseCaution.fromJson(c as Map<String, dynamic>);
        if (caution.productId == null) continue;
        final list = _serverCautions.putIfAbsent(caution.productId!, () => []);
        if (!list.any((x) => x.orderId == caution.orderId)) list.add(caution);
      }
    }
  }

  Future<void> _saveEdit() async {
    final e0 = _editing!;
    final lines = _orderLines;
    final note = _noteValue;
    setState(() {
      _saving = true;
      _saveError = null;
      _info = null;
    });
    try {
      final order = await widget.api.edit(
        e0.orderId,
        version: e0.version,
        note: note,
        lines: lines,
        acks: _ackList,
      );
      if (!mounted) return;
      _saved = true;
      setState(() => _saving = false);
      widget.onSaved(order);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (_unknownOutcome(e)) {
        // Check what is stored: the same version + 1 with our lines = it was saved.
        try {
          final now = await widget.api.get(e0.orderId);
          if (!mounted) return;
          final same =
              now.version > e0.version &&
              now.items
                  .where((i) => i.productId != null)
                  .map((i) => '${i.productId}:${i.quantity}')
                  .toSet()
                  .containsAll(
                    lines.map((l) => '${l.productId}:${l.quantity}'),
                  ) &&
              now.items.where((i) => i.productId != null).length ==
                  lines.length &&
              (now.note ?? '') == (note ?? '');
          if (same) {
            _saved = true;
            setState(() => _saving = false);
            widget.onSaved(now);
            return;
          }
          setState(() {
            _saving = false;
            _saveError = e;
            if (now.version != e0.version)
              _editing = now; // someone else changed it
          });
        } on ApiException {
          if (mounted) {
            setState(() {
              _saving = false;
              _saveError = e;
            });
          }
        }
        return;
      }
      setState(() {
        _saving = false;
        _saveError = e;
        _absorbDetails(e);
      });
      if (e.code == 'ORDER_CHANGED' || e.code == 'INVALID_TRANSITION') {
        try {
          final now = await widget.api.get(e0.orderId);
          if (mounted) setState(() => _editing = now);
        } on ApiException {
          // the error box already explains
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    final title = _editMode
        ? t.editOrderTitle(_editing?.orderNumber ?? '')
        : t.newOrderTitle;
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_loadError != null && _candidates == null && _suppliers.isEmpty) {
      return PurchasePageFrame(
        children: [
          PageHeader(
            title: title,
            description: t.formDescription,
            onBack: widget.onCancel,
            backTooltip: t.back,
          ),
          const SizedBox(height: AppSpacing.xl),
          ApiErrorBox(_loadError!, action: _init),
        ],
      );
    }
    final editingNotPending =
        _editing != null &&
        _editing!.status != PurchaseOrderStatus.pendingApproval;
    return PurchasePageFrame(
      children: [
        PageHeader(
          title: title,
          description: t.formDescription,
          onBack: widget.onCancel,
          backTooltip: t.back,
        ),
        const SizedBox(height: AppSpacing.xl),
        if (editingNotPending) ...[
          NoticeBox.danger(t.readOnlyFinal),
          const SizedBox(height: AppSpacing.lg),
        ],
        if (_unresolved != null) ...[
          NoticeBox.warning(
            key: const Key('poUnresolved'),
            '${t.unresolvedTitle}. ${t.unresolvedBody}',
            child: FilledButton.icon(
              key: const Key('poCheckRetry'),
              onPressed: _saving ? null : () => _send(_unresolved!),
              icon: const Icon(Icons.sync_rounded, size: 18),
              label: Text(t.checkAndRetry),
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
        ],
        _supplierSection(t),
        const SizedBox(height: AppSpacing.lg),
        if (_supplierId != null) ...[
          _productsSection(t),
          const SizedBox(height: AppSpacing.lg),
        ],
        _reviewSection(t, editingNotPending),
      ],
    );
  }

  Widget _supplierSection(PurchaseOrderStrings t) {
    final chosen = _suppliers
        .where((s) => s.supplierId == _supplierId)
        .firstOrNull;
    return SectionCard(
      title: t.stepSupplier,
      icon: Icons.local_shipping_outlined,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: AppSpacing.lg,
            runSpacing: AppSpacing.md,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SizedBox(
                width: 380,
                child: DropdownButtonFormField<int>(
                  key: const Key('poSupplier'),
                  initialValue: _supplierId,
                  isExpanded: true,
                  decoration: InputDecoration(
                    labelText: t.chooseSupplier,
                    isDense: true,
                  ),
                  items: [
                    for (final s in _suppliers)
                      DropdownMenuItem(
                        value: s.supplierId,
                        enabled: s.active,
                        child: Text(
                          s.active
                              ? s.name
                              : '${s.name} ${t.inactiveSupplierOption}',
                          style: TextStyle(
                            color: s.active ? null : AppColors.textMuted,
                          ),
                        ),
                      ),
                  ],
                  // the supplier is fixed when editing or when it comes from the suggestions
                  onChanged: (_editMode || widget.seed != null || _locked)
                      ? null
                      : _chooseSupplier,
                ),
              ),
              Chip(
                avatar: Icon(
                  _source == 'GUARDIAN'
                      ? Icons.shield_outlined
                      : Icons.edit_note_rounded,
                  size: 18,
                ),
                label: Text(
                  _source == 'GUARDIAN' ? t.fromSuggestions : t.manualSource,
                ),
              ),
              if (chosen != null && chosen.hasWhatsapp)
                WhatsappNumber(chosen.whatsappNumber!),
            ],
          ),
          if (chosen != null && !chosen.hasWhatsapp) ...[
            const SizedBox(height: AppSpacing.md),
            NoticeBox.warning(t.supplierNoWhatsappWarning),
          ],
        ],
      ),
    );
  }

  Widget _productsSection(PurchaseOrderStrings t) {
    if (_loadingProducts || _candidates == null) {
      return const Card(
        child: Padding(
          padding: EdgeInsets.all(32),
          child: Center(child: CircularProgressIndicator()),
        ),
      );
    }
    final c = _candidates!;
    final chosen = _lines.map((l) => l.productId).toSet();
    // GUARDIAN orders may only contain current suggestions (the server checks too)
    final offer = [
      for (final p in c.products)
        if (!chosen.contains(p.productId) &&
            (_source != 'GUARDIAN' || _recs.containsKey(p.productId)))
          p,
    ];
    return SectionCard(
      title: t.stepProducts,
      icon: Icons.inventory_2_outlined,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (c.products.isEmpty) Text(t.noProducts),
          for (final p in offer)
            Container(
              key: Key('po-candidate-${p.productId}'),
              padding: const EdgeInsets.symmetric(vertical: 6),
              decoration: const BoxDecoration(
                border: Border(bottom: BorderSide(color: AppColors.divider)),
              ),
              child: Row(
                children: [
                  Expanded(
                    flex: 5,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          p.name,
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        LtrText(
                          p.barcode,
                          style: const TextStyle(
                            fontSize: 12,
                            color: AppColors.textSecondary,
                          ),
                        ),
                        if (!p.available)
                          Text(
                            p.openOrder != null
                                ? t.inOpenOrder(
                                    p.openOrder!.orderNumber,
                                    p.openOrder!.quantity,
                                  )
                                : t.unavailable(p.unavailableReason),
                            style: const TextStyle(
                              fontSize: 12,
                              color: AppColors.danger,
                            ),
                          ),
                      ],
                    ),
                  ),
                  Expanded(
                    flex: 2,
                    child: LtrText('${p.quantity} / ${p.minStock}'),
                  ),
                  Expanded(
                    flex: 2,
                    child: Text(
                      _recs[p.productId]?.suggestedQuantity == null
                          ? ''
                          : '${t.colSuggested}: ${_recs[p.productId]!.suggestedQuantity}',
                    ),
                  ),
                  OutlinedButton.icon(
                    key: Key('po-add-${p.productId}'),
                    onPressed: p.available && !_locked ? () => _add(p) : null,
                    icon: const Icon(Icons.add_rounded, size: 16),
                    label: Text(t.addProductToOrder),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _reviewSection(PurchaseOrderStrings t, bool readOnly) {
    double? total = _lines.isEmpty ? null : 0;
    for (final l in _lines) {
      final q = _qty(l.qty.text);
      if (total == null || l.cost == null || q == null) {
        total = null;
        break;
      }
      total += l.cost! * q;
    }
    final cautions = _cautions;
    return SectionCard(
      title: t.stepReview,
      icon: Icons.fact_check_outlined,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            t.selectedItems,
            style: const TextStyle(fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: AppSpacing.sm),
          if (_lines.isEmpty)
            Text(
              t.noLines,
              style: const TextStyle(color: AppColors.textSecondary),
            ),
          for (final name in _deletedLines)
            NoticeBox.warning('${t.deletedLineWillBeRemoved} (${t.iso(name)})'),
          for (final l in _lines)
            Container(
              key: Key('po-line-${l.productId}'),
              padding: const EdgeInsets.symmetric(vertical: 6),
              decoration: const BoxDecoration(
                border: Border(bottom: BorderSide(color: AppColors.divider)),
              ),
              child: Row(
                children: [
                  Expanded(
                    flex: 5,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          l.name,
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        LtrText(
                          l.barcode,
                          style: const TextStyle(
                            fontSize: 12,
                            color: AppColors.textSecondary,
                          ),
                        ),
                        if (l.suggested != null)
                          Text(
                            '${t.colSuggested}: ${l.suggested}${_qty(l.qty.text) != null && _qty(l.qty.text) != l.suggested ? ' — ${t.changedQuantity}' : ''}',
                            style: const TextStyle(
                              fontSize: 12,
                              color: AppColors.textSecondary,
                            ),
                          ),
                      ],
                    ),
                  ),
                  SizedBox(
                    width: 140,
                    child: TextField(
                      key: Key('po-qty-${l.productId}'),
                      controller: l.qty,
                      enabled: !_locked && !readOnly,
                      keyboardType: TextInputType.number,
                      decoration: InputDecoration(
                        labelText: t.colQuantity,
                        isDense: true,
                        errorText: _qty(l.qty.text) == null
                            ? t.quantityError
                            : null,
                      ),
                      onChanged: (_) => setState(() {}),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  SizedBox(
                    width: 130,
                    child: Text(
                      l.cost == null
                          ? t.unknownCost
                          : '${t.expectedCost}: ${Formatters.currency(l.cost!)}',
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      ),
                    ),
                  ),
                  IconButton(
                    key: Key('po-remove-${l.productId}'),
                    tooltip: t.removeLine,
                    onPressed: _locked || readOnly ? null : () => _remove(l),
                    icon: const Icon(
                      Icons.delete_outline_rounded,
                      color: AppColors.danger,
                    ),
                  ),
                ],
              ),
            ),
          if (_lines.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.sm),
            Text(
              '${t.totalExpected}: ${total == null ? t.unknownCost : Formatters.currency(total)} — ${t.noCostInMessage}',
              style: const TextStyle(
                fontSize: 12.5,
                color: AppColors.textSecondary,
              ),
            ),
          ],
          if (cautions.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.lg),
            NoticeBox.warning(
              key: const Key('poCautions'),
              '${t.cautionsTitle}: ${t.cautionsBody}',
              child: Column(
                children: [
                  for (final c in cautions)
                    CheckboxListTile(
                      key: Key('po-ack-${c.$1}-${c.$3.orderId}'),
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      controlAffinity: ListTileControlAffinity.leading,
                      value: _acks.contains(CautionAck(c.$1, c.$3.orderId)),
                      onChanged: _locked
                          ? null
                          : (v) => setState(() {
                              final a = CautionAck(c.$1, c.$3.orderId);
                              v == true ? _acks.add(a) : _acks.remove(a);
                              _dirty = true;
                            }),
                      title: Text(
                        '${c.$2}: ${t.cautionTitle(c.$3.type)}',
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      subtitle: Text(
                        '${t.cautionBody(c.$3)} ${t.ackCaution}.',
                        style: const TextStyle(fontSize: 12),
                      ),
                    ),
                ],
              ),
            ),
          ],
          const SizedBox(height: AppSpacing.lg),
          TextField(
            key: const Key('poNote'),
            controller: _note,
            enabled: !_locked && !readOnly,
            maxLines: 3,
            decoration: InputDecoration(
              labelText: t.noteLabel,
              helperText: t.noteHelp,
              errorText: _note.text.trim().runes.length > 500
                  ? t.noteTooLong
                  : null,
            ),
          ),
          if (_editing?.messageLength != null) ...[
            const SizedBox(height: AppSpacing.lg),
            Text(
              t.lengthTitle,
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: AppSpacing.xs),
            for (final r in _editing!.messageLength!.values)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: LengthMeter(r),
              ),
          ],
          if (_saveError != null && _unresolved == null) ...[
            const SizedBox(height: AppSpacing.lg),
            ApiErrorBox(_saveError!),
          ],
          if (_info != null) ...[
            const SizedBox(height: AppSpacing.md),
            NoticeBox(text: _info!),
          ],
          const SizedBox(height: AppSpacing.lg),
          Wrap(
            spacing: AppSpacing.md,
            alignment: WrapAlignment.end,
            children: [
              OutlinedButton(
                key: const Key('poFormCancel'),
                onPressed: _saving ? null : widget.onCancel,
                child: Text(t.cancel),
              ),
              FilledButton.icon(
                key: const Key('poFormSave'),
                onPressed: _valid && !_locked && !readOnly ? _save : null,
                icon: _saving
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Icon(Icons.save_rounded, size: 18),
                label: Text(_editMode ? t.saveChanges : t.save),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
