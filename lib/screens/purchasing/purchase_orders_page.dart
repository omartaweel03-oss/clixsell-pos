import 'dart:async';

import 'package:flutter/material.dart';

import '../../l10n/purchase_order_strings.dart';
import '../../models/purchase_order.dart';
import '../../services/api_http.dart';
import '../../services/purchase_api_client.dart';
import '../../theme/app_theme.dart';
import '../../widgets/min_width_scroll.dart';
import '../../widgets/page_header.dart';
import '../../widgets/stat_card.dart';
import 'purchase_widgets.dart';

/// Screen 1 — Purchase Orders: the store's orders with status filter,
/// server-side search (all pages), paging and summary cards.
class PurchaseOrdersPage extends StatefulWidget {
  const PurchaseOrdersPage({
    super.key,
    required this.api,
    required this.onOpenOrder,
    required this.onNewOrder,
    this.onOverdueCount,
  });

  final PurchaseApi api;
  final ValueChanged<int> onOpenOrder;
  final VoidCallback onNewOrder;

  /// Reports the number of overdue open orders (for the sidebar badge).
  final ValueChanged<int>? onOverdueCount;

  @override
  State<PurchaseOrdersPage> createState() => _PurchaseOrdersPageState();
}

/// A status filter: null = all.
typedef _Filter = List<PurchaseOrderStatus>?;

class _Summary {
  const _Summary(
    this.pending,
    this.toContact,
    this.awaitingSupplier,
    this.overdue,
  );

  final int pending;
  final int toContact;
  final int awaitingSupplier;
  final int overdue;
}

class _PurchaseOrdersPageState extends State<PurchaseOrdersPage> {
  static const _pageSize = 20;

  final _search = TextEditingController();
  Timer? _debounce;
  String _query = '';
  _Filter _filter;
  bool _overdueOnly = false;
  int _page = 0;
  PurchaseOrderPage? _data;
  _Summary? _summary;
  bool _loading = true;
  ApiException? _error;
  int _request = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _search.dispose();
    super.dispose();
  }

  /// Loads the page and, in parallel, the exact summary from the server
  /// (D1: counted by the database — no paging, no limit, nothing twice).
  Future<void> _load() async {
    final req = ++_request;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final results = await Future.wait<Object>([
        widget.api.list(
          statuses: _filter ?? const [],
          query: _query,
          overdueOnly: _overdueOnly,
          page: _page,
          size: _pageSize,
        ),
        widget.api.summary(),
      ]);
      if (!mounted || req != _request) return;
      final sum = results[1] as PurchaseOrdersSummary;
      final summary = _Summary(
        sum.count(PurchaseOrderStatus.pendingApproval),
        sum.count(PurchaseOrderStatus.approved) +
            sum.count(PurchaseOrderStatus.messageGenerated),
        sum.count(PurchaseOrderStatus.sent),
        sum.overdue,
      );
      widget.onOverdueCount?.call(summary.overdue);
      setState(() {
        _data = results[0] as PurchaseOrderPage;
        _summary = summary;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted || req != _request) return;
      setState(() {
        _error = e;
        _loading = false;
      });
    }
  }

  void _onSearch(String text) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 350), () {
      if (!mounted) return;
      _query = text.trim();
      _page = 0;
      _load();
    });
  }

  void _setFilter(_Filter f, {bool overdue = false}) {
    setState(() {
      _filter = f;
      _overdueOnly = overdue;
      _page = 0;
    });
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    final s = _summary;
    return PurchasePageFrame(
      children: [
        PageHeader(
          title: t.ordersTitle,
          description: t.ordersDescription,
          action: FilledButton.icon(
            key: const Key('newPurchaseOrderButton'),
            onPressed: widget.onNewOrder,
            icon: const Icon(Icons.add_shopping_cart_rounded, size: 20),
            label: Text(t.newOrder),
          ),
        ),
        const SizedBox(height: AppSpacing.xl),
        LayoutBuilder(
          builder: (context, c) {
            final cards = [
              StatCard(
                key: const Key('poCardPending'),
                title: t.cardPending,
                value: s == null ? '—' : '${s.pending}',
                icon: Icons.hourglass_top_rounded,
                color: AppColors.warning,
                softColor: AppColors.warningSoft,
                selected:
                    !_overdueOnly &&
                    _filter?.length == 1 &&
                    _filter!.first == PurchaseOrderStatus.pendingApproval,
                onTap: () => _setFilter([PurchaseOrderStatus.pendingApproval]),
              ),
              StatCard(
                key: const Key('poCardToContact'),
                title: t.cardToContact,
                value: s == null ? '—' : '${s.toContact}',
                icon: Icons.chat_outlined,
                color: AppColors.primary,
                softColor: AppColors.primarySoft,
                selected: !_overdueOnly && _filter?.length == 2,
                onTap: () => _setFilter([
                  PurchaseOrderStatus.approved,
                  PurchaseOrderStatus.messageGenerated,
                ]),
              ),
              StatCard(
                key: const Key('poCardAwaiting'),
                title: t.cardAwaitingSupplier,
                value: s == null ? '—' : '${s.awaitingSupplier}',
                icon: Icons.schedule_send_outlined,
                color: AppColors.info,
                softColor: AppColors.infoSoft,
                selected:
                    !_overdueOnly &&
                    _filter?.length == 1 &&
                    _filter!.first == PurchaseOrderStatus.sent,
                onTap: () => _setFilter([PurchaseOrderStatus.sent]),
              ),
              StatCard(
                key: const Key('poCardOverdue'),
                title: t.cardOverdue,
                value: s == null ? '—' : '${s.overdue}',
                subtitle: t.cardOpenHint,
                icon: Icons.alarm_rounded,
                color: AppColors.danger,
                softColor: AppColors.dangerSoft,
                selected: _overdueOnly,
                onTap: () => _setFilter(null, overdue: true),
              ),
            ];
            final perRow = c.maxWidth >= 900 ? 4 : 2;
            final w = (c.maxWidth - AppSpacing.lg * (perRow - 1)) / perRow;
            return Wrap(
              spacing: AppSpacing.lg,
              runSpacing: AppSpacing.lg,
              children: [for (final x in cards) SizedBox(width: w, child: x)],
            );
          },
        ),
        const SizedBox(height: AppSpacing.xl),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.lg + 4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _toolbar(t),
                const SizedBox(height: AppSpacing.lg),
                _body(t),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _toolbar(PurchaseOrderStrings t) {
    Widget chip(String label, _Filter f, String key) {
      final selected =
          !_overdueOnly &&
          ((f == null && _filter == null) ||
              (f != null &&
                  _filter != null &&
                  f.length == _filter!.length &&
                  f.every(_filter!.contains)));
      return ChoiceChip(
        key: Key('poFilter-$key'),
        label: Text(label),
        selected: selected,
        onSelected: (_) => _setFilter(f),
      );
    }

    return Wrap(
      spacing: AppSpacing.lg,
      runSpacing: AppSpacing.md,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        SizedBox(
          width: 340,
          child: TextField(
            key: const Key('purchaseOrderSearch'),
            controller: _search,
            onChanged: _onSearch,
            maxLength: 80,
            decoration: InputDecoration(
              labelText: t.searchOrders,
              prefixIcon: const Icon(Icons.search_rounded),
              isDense: true,
              counterText: '',
            ),
          ),
        ),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            chip(t.filterAll, null, 'all'),
            for (final st in PurchaseOrderStatus.values)
              chip(t.statusShort(st), [st], st.code),
          ],
        ),
      ],
    );
  }

  Widget _body(PurchaseOrderStrings t) {
    if (_loading && _data == null) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 56),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (_error != null) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
        child: ApiErrorBox(
          _error!,
          action: _load,
          actionLabel: t.retry,
          extra: t.couldNotLoad,
        ),
      );
    }
    final data = _data!;
    final rows = data.orders;
    if (rows.isEmpty) {
      final nothingAtAll = _query.isEmpty && _filter == null && !_overdueOnly;
      return MessageState(
        icon: nothingAtAll
            ? Icons.receipt_long_outlined
            : Icons.search_off_rounded,
        title: nothingAtAll ? t.noOrdersTitle : t.noMatchTitle,
        body: nothingAtAll ? t.noOrdersBody : t.noMatchBody,
        actionLabel: nothingAtAll ? t.newOrder : null,
        onAction: nothingAtAll ? widget.onNewOrder : null,
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (_loading) const LinearProgressIndicator(minHeight: 2),
        _table(t, rows),
        _pager(t, data),
      ],
    );
  }

  Widget _table(PurchaseOrderStrings t, List<PurchaseOrderSummary> rows) {
    const header = TextStyle(
      fontSize: 12.5,
      fontWeight: FontWeight.w700,
      color: AppColors.textSecondary,
    );
    return MinWidthScroll(
      minWidth: 820,
      child: Column(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
            decoration: BoxDecoration(
              color: AppColors.background,
              borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
            ),
            child: Row(
              children: [
                Expanded(flex: 3, child: Text(t.colNumber, style: header)),
                Expanded(flex: 4, child: Text(t.colSupplier, style: header)),
                Expanded(flex: 2, child: Text(t.colItems, style: header)),
                Expanded(flex: 2, child: Text(t.colSource, style: header)),
                Expanded(flex: 4, child: Text(t.colStatus, style: header)),
                Expanded(flex: 3, child: Text(t.colCreated, style: header)),
                Expanded(flex: 2, child: Text(t.colActions, style: header)),
              ],
            ),
          ),
          for (final o in rows)
            InkWell(
              key: ValueKey('po-row-${o.orderId}'),
              onTap: () => widget.onOpenOrder(o.orderId),
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 10,
                ),
                decoration: const BoxDecoration(
                  border: Border(bottom: BorderSide(color: AppColors.divider)),
                ),
                child: Row(
                  children: [
                    Expanded(
                      flex: 3,
                      child: Row(
                        children: [
                          Flexible(
                            child: LtrText(
                              o.orderNumber,
                              style: const TextStyle(
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                          ),
                          if (o.overdue)
                            Padding(
                              padding: const EdgeInsetsDirectional.only(
                                start: 6,
                              ),
                              child: Tooltip(
                                message: t.overdueExplain,
                                child: const Icon(
                                  Icons.alarm_rounded,
                                  size: 17,
                                  color: AppColors.danger,
                                  semanticLabel: 'overdue',
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                    Expanded(
                      flex: 4,
                      child: Text(
                        o.supplierName,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    Expanded(flex: 2, child: Text('${o.itemCount}')),
                    Expanded(flex: 2, child: Text(t.source(o.source))),
                    Expanded(
                      flex: 4,
                      child: Align(
                        alignment: AlignmentDirectional.centerStart,
                        child: PurchaseStatusChip(o.status),
                      ),
                    ),
                    Expanded(
                      flex: 3,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          LtrText(
                            shortTime(o.createdAt),
                            style: const TextStyle(fontSize: 12.5),
                          ),
                          if (o.createdBy != null)
                            Text(
                              o.createdBy!.label,
                              style: const TextStyle(
                                fontSize: 11.5,
                                color: AppColors.textSecondary,
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                        ],
                      ),
                    ),
                    Expanded(
                      flex: 2,
                      child: Align(
                        alignment: AlignmentDirectional.centerStart,
                        child: Tooltip(
                          message: t.openOrderTooltip(o.orderNumber),
                          child: OutlinedButton(
                            key: Key('po-open-${o.orderId}'),
                            onPressed: () => widget.onOpenOrder(o.orderId),
                            child: Text(t.open),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _pager(PurchaseOrderStrings t, PurchaseOrderPage data) {
    final from = data.total == 0 ? 0 : data.page * data.size + 1;
    final to = (data.page * data.size + data.orders.length);
    return Padding(
      padding: const EdgeInsets.only(top: AppSpacing.md),
      child: Row(
        children: [
          Expanded(
            child: Text(
              t.showing(from, to, data.total),
              style: const TextStyle(
                color: AppColors.textSecondary,
                fontSize: 12.5,
              ),
            ),
          ),
          IconButton(
            key: const Key('poPrevPage'),
            tooltip: '‹',
            onPressed: _page > 0 && !_loading
                ? () {
                    setState(() => _page--);
                    _load();
                  }
                : null,
            icon: const Icon(Icons.chevron_left_rounded),
          ),
          Text('${data.page + 1} / ${data.pageCount}'),
          IconButton(
            key: const Key('poNextPage'),
            tooltip: '›',
            onPressed: _page + 1 < data.pageCount && !_loading
                ? () {
                    setState(() => _page++);
                    _load();
                  }
                : null,
            icon: const Icon(Icons.chevron_right_rounded),
          ),
        ],
      ),
    );
  }
}
