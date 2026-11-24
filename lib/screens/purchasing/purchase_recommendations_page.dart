import 'package:flutter/material.dart';

import '../../l10n/purchase_order_strings.dart';
import '../../models/purchase_order.dart';
import '../../services/api_http.dart';
import '../../services/purchase_api_client.dart';
import '../../theme/app_theme.dart';
import '../../widgets/min_width_scroll.dart';
import '../../widgets/page_header.dart';
import 'purchase_widgets.dart';

/// What the order form starts with when opened from the suggestions: one
/// supplier, Guardian source, and the server's suggested quantities (the
/// manager may change them; the server checks everything again on save).
class PurchaseOrderSeed {
  const PurchaseOrderSeed({
    required this.supplierId,
    required this.source,
    required this.lines,
  });

  final int supplierId;
  final String source;
  final List<OrderLine> lines;
}

/// Screen 2 — Reorder Suggestions (Guardian, Figure 3.1 / 3.18). Rule-based:
/// never presented as an AI model. With Guardian Mode off nothing is shown
/// instead; manual orders stay available.
class PurchaseRecommendationsPage extends StatefulWidget {
  const PurchaseRecommendationsPage({
    super.key,
    required this.api,
    required this.onCreateOrder,
    required this.onManualOrder,
    required this.onOpenOrder,
  });

  final PurchaseApi api;
  final ValueChanged<PurchaseOrderSeed> onCreateOrder;
  final VoidCallback onManualOrder;
  final ValueChanged<int> onOpenOrder;

  @override
  State<PurchaseRecommendationsPage> createState() =>
      _PurchaseRecommendationsPageState();
}

class _PurchaseRecommendationsPageState
    extends State<PurchaseRecommendationsPage> {
  PurchaseRecommendations? _data;
  ApiException? _error;
  bool _loading = true;
  int? _supplier;
  final Set<int> _selected = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final d = await widget.api.recommendations();
      if (!mounted) return;
      setState(() {
        _data = d;
        _loading = false;
        _selected.clear();
        _supplier = null;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e;
        _loading = false;
      });
    }
  }

  void _toggle(SupplierRecommendations s, RecommendationItem r, bool on) {
    setState(() {
      if (on) {
        if (_supplier != s.supplierId) {
          _selected.clear(); // one supplier per order
        }
        _supplier = s.supplierId;
        _selected.add(r.productId);
      } else {
        _selected.remove(r.productId);
        if (_selected.isEmpty) _supplier = null;
      }
    });
  }

  void _create() {
    final s = _data!.suppliers.firstWhere((x) => x.supplierId == _supplier);
    widget.onCreateOrder(
      PurchaseOrderSeed(
        supplierId: s.supplierId,
        source: 'GUARDIAN',
        lines: [
          for (final r in s.items)
            if (_selected.contains(r.productId))
              OrderLine(r.productId, r.suggestedQuantity ?? 1),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    return PurchasePageFrame(
      children: [
        PageHeader(
          title: t.recTitle,
          description: t.recDescription,
          action: Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.sm,
            children: [
              OutlinedButton.icon(
                key: const Key('recRefresh'),
                onPressed: _loading ? null : _load,
                icon: const Icon(Icons.refresh_rounded, size: 18),
                label: Text(t.refresh),
              ),
              OutlinedButton.icon(
                key: const Key('recManualOrder'),
                onPressed: widget.onManualOrder,
                icon: const Icon(Icons.edit_note_rounded, size: 18),
                label: Text(t.manualOrder),
              ),
              FilledButton.icon(
                key: const Key('recCreateOrder'),
                onPressed: _selected.isEmpty ? null : _create,
                icon: const Icon(Icons.add_shopping_cart_rounded, size: 18),
                label: Text(t.createFromSelected(_selected.length)),
              ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.xl),
        ..._body(t),
      ],
    );
  }

  List<Widget> _body(PurchaseOrderStrings t) {
    if (_loading) {
      return const [
        Padding(
          padding: EdgeInsets.symmetric(vertical: 56),
          child: Center(child: CircularProgressIndicator()),
        ),
      ];
    }
    if (_error != null) return [ApiErrorBox(_error!, action: _load)];
    final d = _data!;
    if (!d.guardianEnabled) {
      return [
        Card(
          child: MessageState(
            key: const Key('recGuardianOff'),
            icon: Icons.shield_outlined,
            title: t.guardianOffTitle,
            body: t.guardianOffBody,
            actionLabel: t.manualOrder,
            actionIcon: Icons.edit_note_rounded,
            onAction: widget.onManualOrder,
          ),
        ),
      ];
    }
    return [
      NoticeBox(text: t.rulesNote, icon: Icons.rule_rounded),
      const SizedBox(height: AppSpacing.md),
      if (_selected.isEmpty)
        Text(
          t.selectOneSupplier,
          style: const TextStyle(color: AppColors.textSecondary),
        ),
      const SizedBox(height: AppSpacing.md),
      if (d.suppliers.isEmpty)
        Card(
          child: MessageState(
            key: const Key('recEmpty'),
            icon: Icons.inventory_rounded,
            title: t.noRecommendations,
            body: t.noRecommendationsBody,
          ),
        ),
      for (final s in d.suppliers) ...[
        _supplierCard(t, s),
        const SizedBox(height: AppSpacing.lg),
      ],
      if (d.inconsistent.isNotEmpty)
        SectionCard(
          key: const Key('recInconsistent'),
          title: t.inconsistentTitle,
          icon: Icons.report_problem_outlined,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                t.inconsistentBody,
                style: const TextStyle(color: AppColors.textSecondary),
              ),
              const SizedBox(height: AppSpacing.sm),
              for (final r in d.inconsistent)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Text(
                    '• ${r.name} — ${t.explanation(r)}',
                    style: const TextStyle(fontSize: 13),
                  ),
                ),
            ],
          ),
        ),
    ];
  }

  Widget _supplierCard(PurchaseOrderStrings t, SupplierRecommendations s) {
    const header = TextStyle(
      fontSize: 12,
      fontWeight: FontWeight.w700,
      color: AppColors.textSecondary,
    );
    return SectionCard(
      key: Key('rec-supplier-${s.supplierId}'),
      title: s.supplierName,
      icon: Icons.local_shipping_outlined,
      trailing: Wrap(
        spacing: 6,
        children: [
          if (!s.active) _tag(t.supplierInactive, AppColors.danger),
          if (!s.hasWhatsapp) _tag(t.noWhatsapp, AppColors.warning),
        ],
      ),
      child: MinWidthScroll(
        minWidth: 1000,
        child: Column(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
              decoration: BoxDecoration(
                color: AppColors.background,
                borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
              ),
              child: Row(
                children: [
                  const SizedBox(width: 44),
                  Expanded(flex: 4, child: Text(t.colProduct, style: header)),
                  Expanded(flex: 2, child: Text(t.colStock, style: header)),
                  Expanded(flex: 3, child: Text(t.colUrgency, style: header)),
                  Expanded(flex: 2, child: Text(t.colSuggested, style: header)),
                  Expanded(flex: 6, child: Text(t.colWhy, style: header)),
                ],
              ),
            ),
            for (final r in s.items) _row(t, s, r),
          ],
        ),
      ),
    );
  }

  Widget _row(
    PurchaseOrderStrings t,
    SupplierRecommendations s,
    RecommendationItem r,
  ) {
    final urgent =
        r.classification == 'OUT_OF_STOCK' || r.classification == 'URGENT';
    return Container(
      key: Key('rec-item-${r.productId}'),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: AppColors.divider)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 44,
            child: Checkbox(
              key: Key('rec-select-${r.productId}'),
              value: _selected.contains(r.productId),
              onChanged: r.available ? (v) => _toggle(s, r, v ?? false) : null,
            ),
          ),
          Expanded(
            flex: 4,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  r.name,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
                LtrText(
                  r.barcode,
                  style: const TextStyle(
                    fontSize: 12,
                    color: AppColors.textSecondary,
                  ),
                ),
                if (!r.available)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: r.openOrder != null
                        ? InkWell(
                            onTap: () =>
                                widget.onOpenOrder(r.openOrder!.orderId),
                            child: Text(
                              t.inOpenOrder(
                                r.openOrder!.orderNumber,
                                r.openOrder!.quantity,
                              ),
                              style: const TextStyle(
                                color: AppColors.info,
                                fontSize: 12,
                                decoration: TextDecoration.underline,
                              ),
                            ),
                          )
                        : Text(
                            t.unavailable(r.unavailableReason),
                            style: const TextStyle(
                              color: AppColors.danger,
                              fontSize: 12,
                            ),
                          ),
                  ),
              ],
            ),
          ),
          Expanded(flex: 2, child: LtrText('${r.quantity} / ${r.minStock}')),
          Expanded(
            flex: 3,
            child: Align(
              alignment: AlignmentDirectional.centerStart,
              child: _tag(
                t.classification(r.classification),
                urgent ? AppColors.danger : AppColors.warning,
              ),
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              r.suggestedQuantity == null
                  ? t.noSuggestedQty
                  : '${r.suggestedQuantity}',
              style: TextStyle(
                fontWeight: FontWeight.w800,
                fontSize: r.suggestedQuantity == null ? 11.5 : 14,
                color: r.suggestedQuantity == null
                    ? AppColors.textSecondary
                    : AppColors.textPrimary,
              ),
            ),
          ),
          Expanded(
            flex: 6,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  t.explanation(r),
                  style: const TextStyle(
                    fontSize: 12,
                    height: 1.4,
                    color: AppColors.textSecondary,
                  ),
                ),
                for (final c in r.cautions) CautionTile(c),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _tag(String text, Color color) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.1),
      borderRadius: BorderRadius.circular(14),
    ),
    child: Text(
      text,
      style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w700),
    ),
  );
}
