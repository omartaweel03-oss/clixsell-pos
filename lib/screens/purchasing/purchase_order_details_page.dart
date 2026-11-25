import 'package:flutter/material.dart';

import '../../l10n/purchase_order_strings.dart';
import '../../models/purchase_order.dart';
import '../../services/api_http.dart';
import '../../services/purchase_api_client.dart';
import '../../services/url_opener.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/min_width_scroll.dart';
import '../../widgets/page_header.dart';
import 'purchase_order_dialogs.dart';
import 'purchase_widgets.dart';

/// Screen 4 — one purchase order: status and progress, the actions the
/// backend allows (allowedActions), the supplier message and communication
/// records, the items and the full history. Final orders are read only.
class PurchaseOrderDetailsPage extends StatefulWidget {
  const PurchaseOrderDetailsPage({
    super.key,
    required this.api,
    required this.orderId,
    required this.onBack,
    required this.onEdit,
    this.onOpenSuppliers,
    this.opener = const LauncherUrlOpener(),
    this.clipboard = const SystemClipboardWriter(),
    this.currentUserId,
  });

  final PurchaseApi api;
  final int orderId;
  final VoidCallback onBack;
  final ValueChanged<int> onEdit;
  final VoidCallback? onOpenSuppliers;
  final UrlOpener opener;
  final ClipboardWriter clipboard;

  /// The logged-in manager (D3: explains whose change won a conflict).
  final int? currentUserId;

  @override
  State<PurchaseOrderDetailsPage> createState() =>
      _PurchaseOrderDetailsPageState();
}

class _PurchaseOrderDetailsPageState extends State<PurchaseOrderDetailsPage> {
  PurchaseOrder? _order;
  List<PurchaseOrderEvent> _events = const [];
  ApiException? _loadError;
  ApiException? _actionError;
  String? _notice;
  bool _loading = true;
  bool _busy = false;
  int _tab = 0;

  /// A successful copy whose history record is not saved yet (same id on retry).
  String? _pendingCopyId;

  /// D3: after an unknown outcome that was not saved yet, "Check again"
  /// reloads and tests this again (nothing is resent).
  bool Function(PurchaseOrder)? _unconfirmed;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<PurchaseOrder?> _load({bool quiet = false}) async {
    if (!quiet) {
      setState(() {
        _loading = true;
        _loadError = null;
      });
    }
    try {
      final results = await Future.wait([
        widget.api.get(widget.orderId),
        widget.api.events(widget.orderId),
      ]);
      if (!mounted) return null;
      setState(() {
        _order = results[0] as PurchaseOrder;
        _events = results[1] as List<PurchaseOrderEvent>;
        _loading = false;
      });
      return _order;
    } on ApiException catch (e) {
      if (!mounted) return null;
      setState(() {
        _loadError = e;
        _loading = false;
      });
      return null;
    }
  }

  static bool _unknownOutcome(ApiException e) =>
      e.isNetworkError ||
      e.code == 'DATABASE_BUSY' ||
      (e.statusCode != null && e.statusCode! >= 500);

  /// Runs one change. Stale version → reload and let the manager decide again
  /// (never replayed automatically). Unknown outcome → reload and check
  /// whether the change is stored before saying anything.
  Future<void> _run(
    Future<PurchaseOrder> Function() call, {
    required bool Function(PurchaseOrder) applied,
    String? success,
  }) async {
    final t = PurchaseOrderStrings.of(context);
    setState(() {
      _busy = true;
      _actionError = null;
      _notice = null;
      _unconfirmed = null;
    });
    try {
      await call();
      await _load(quiet: true);
      if (mounted && success != null) setState(() => _notice = success);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (_unknownOutcome(e)) {
        final now = await _load(quiet: true);
        if (!mounted) return;
        setState(() {
          if (now != null && applied(now)) {
            _notice = t.savedAfterCheck;
          } else {
            _actionError = e;
            _notice = now == null ? null : t.notSavedAfterCheck;
            _unconfirmed = applied;
          }
        });
      } else {
        if (e.code == 'ORDER_CHANGED' ||
            e.code == 'INVALID_TRANSITION' ||
            e.code == 'ORDER_FINAL') {
          await _load(quiet: true);
        }
        if (mounted) setState(() => _actionError = e);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// D3: reloads the saved state and tells whether the earlier, unconfirmed
  /// change is stored now. Never sends the change again.
  Future<void> _checkAgain() async {
    final t = PurchaseOrderStrings.of(context);
    final applied = _unconfirmed;
    if (applied == null) return;
    setState(() => _busy = true);
    final now = await _load(quiet: true);
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (now != null && applied(now)) {
        _notice = t.savedAfterCheck;
        _actionError = null;
        _unconfirmed = null;
      } else if (now != null) {
        _notice = t.notSavedAfterCheck;
      }
    });
  }

  // ── actions ────────────────────────────────────────────────────────────────

  Future<void> _approve(PurchaseOrder o) async {
    final t = PurchaseOrderStrings.of(context);
    final ok = await confirmAction(
      context,
      title: t.approveTitle,
      body: t.approveBody,
      confirmLabel: t.actApprove,
      cancelLabel: t.cancel,
      icon: Icons.verified_outlined,
      color: AppColors.success,
    );
    if (!ok || !mounted) return;
    await _run(
      () => widget.api.approve(o.orderId, o.version),
      applied: (n) => n.status == PurchaseOrderStatus.approved,
      success: t.status(PurchaseOrderStatus.approved),
    );
  }

  Future<void> _reject(PurchaseOrder o) async {
    final t = PurchaseOrderStrings.of(context);
    final reason = await showRejectDialog(context);
    if (reason == null || !mounted) return;
    await _run(
      () => widget.api.reject(o.orderId, o.version, reason),
      applied: (n) => n.status == PurchaseOrderStatus.rejected,
      success: t.status(PurchaseOrderStatus.rejected),
    );
  }

  Future<void> _cancel(PurchaseOrder o) async {
    final t = PurchaseOrderStrings.of(context);
    final choice = await showCancelDialog(context, o);
    if (choice == null || !mounted) return;
    if (choice.recordSendingFirst) {
      setState(() => _notice = t.sentExplain);
      return _markSent(o);
    }
    await _run(
      () => widget.api.cancel(
        o.orderId,
        version: o.version,
        reason: choice.reason,
        contactStatus: choice.contactStatus,
        confirmNotSent: choice.confirmNotSent,
        acknowledgeSupplierNotNotified: choice.acknowledgeSupplierNotNotified,
      ),
      applied: (n) => n.status == PurchaseOrderStatus.canceled,
      success: t.cancelNotTold,
    );
  }

  Future<void> _prepare(PurchaseOrder o) async {
    final t = PurchaseOrderStrings.of(context);
    final saved = await showPreviewDialog(
      context,
      api: widget.api,
      order: o,
      onOpenSuppliers: widget.onOpenSuppliers,
    );
    if (!mounted) return;
    await _load(
      quiet: true,
    ); // also after a failure: the order may have changed
    if (saved != null && mounted) setState(() => _notice = t.prepared);
  }

  Future<void> _open(PurchaseOrder o) async {
    await showOpenWhatsappDialog(
      context,
      api: widget.api,
      opener: widget.opener,
      order: o,
    );
    if (mounted) await _load(quiet: true);
  }

  Future<void> _copy(PurchaseOrder o) async {
    final t = PurchaseOrderStrings.of(context);
    final ok = await widget.clipboard.copy(o.message!.text);
    if (!mounted) return;
    if (!ok) {
      // nothing is recorded: the copy did not happen
      setState(() => _notice = t.copyFailed);
      return;
    }
    _pendingCopyId = newClientId();
    await _reportCopy(o.orderId);
  }

  /// Records the copy; on failure keeps the same id so a retry is recorded once.
  Future<void> _reportCopy(int orderId) async {
    final t = PurchaseOrderStrings.of(context);
    final id = _pendingCopyId;
    if (id == null) return;
    setState(() {
      _busy = true;
      _actionError = null;
    });
    try {
      await widget.api.messageCopied(orderId, id);
      _pendingCopyId = null;
      await _load(quiet: true);
      if (mounted) setState(() => _notice = t.copied);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'ORDER_FINAL' ||
          e.code == 'CLIENT_ACTION_CONFLICT' ||
          e.code == 'MESSAGE_NOT_GENERATED') {
        _pendingCopyId =
            null; // cannot be recorded any more; the copy itself happened
      }
      setState(() {
        _notice = t.copied;
        _actionError = e;
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _markSent(PurchaseOrder o) async {
    final t = PurchaseOrderStrings.of(context);
    final r = await showCheckDialog(
      context,
      key: const Key('markSentDialog'),
      title: t.sentTitle,
      check: t.sentCheck(formatPlus(o.message?.whatsappNumber ?? '')),
      body: t.sentNote,
      confirmLabel: t.recordSending,
      icon: Icons.outgoing_mail,
    );
    if (r == null || !mounted) return;
    await _run(
      () => widget.api.markSent(o.orderId, o.version),
      applied: (n) => n.status == PurchaseOrderStatus.sent,
      success: t.status(PurchaseOrderStatus.sent),
    );
  }

  Future<void> _confirm(PurchaseOrder o) async {
    final t = PurchaseOrderStrings.of(context);
    final r = await showCheckDialog(
      context,
      key: const Key('supplierConfirmDialog'),
      title: t.confirmTitle,
      check: t.confirmCheck,
      body: t.sentNote,
      noteLabel: t.confirmNoteLabel,
      confirmLabel: t.recordConfirmation,
      icon: Icons.handshake_outlined,
      color: AppColors.success,
    );
    if (r == null || !mounted) return;
    await _run(
      () => widget.api.supplierConfirmation(o.orderId, o.version, r.$2),
      applied: (n) => n.status == PurchaseOrderStatus.confirmed,
      success: t.status(PurchaseOrderStatus.confirmed),
    );
  }

  Future<void> _close(PurchaseOrder o) async {
    final t = PurchaseOrderStrings.of(context);
    final r = await showCheckDialog(
      context,
      key: const Key('closeDialog'),
      title: t.closeTitle,
      check: t.closeCheck,
      body: t.closeBody,
      confirmLabel: t.closeOrder,
      icon: Icons.lock_outline_rounded,
      color: AppColors.textSecondary,
    );
    if (r == null || !mounted) return;
    await _run(
      () => widget.api.close(o.orderId, o.version),
      applied: (n) => n.status == PurchaseOrderStatus.closed,
      success: t.status(PurchaseOrderStatus.closed),
    );
  }

  Future<void> _supplierCancel(PurchaseOrder o) async {
    final t = PurchaseOrderStrings.of(context);
    final r = await showSupplierCancellationDialog(context);
    if (r == null || !mounted) return;
    await _run(
      () =>
          widget.api.supplierCancellation(o.orderId, channel: r.$1, note: r.$2),
      applied: (n) => n.communication.supplierCancellationRecorded,
      success: t.supplierAcceptanceRecorded,
    );
  }

  // ── build ──────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    if (_loading && _order == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_order == null) {
      return PurchasePageFrame(
        children: [
          PageHeader(
            title: t.ordersTitle,
            description: '',
            onBack: widget.onBack,
            backTooltip: t.back,
          ),
          const SizedBox(height: AppSpacing.xl),
          ApiErrorBox(
            _loadError!,
            action: _loadError!.isNotFound ? widget.onBack : _load,
            actionLabel: _loadError!.isNotFound ? t.back : t.retry,
          ),
        ],
      );
    }
    final o = _order!;
    return PurchasePageFrame(
      children: [
        PageHeader(
          title: o.orderNumber,
          description:
              '${o.supplierName} · ${t.source(o.source)} · ${t.createdLabel} ${shortTime(o.createdAt)}'
              '${o.createdBy == null ? '' : ' · ${o.createdBy!.label}'}',
          onBack: widget.onBack,
          backTooltip: t.back,
          action: Wrap(
            spacing: AppSpacing.sm,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              PurchaseStatusChip(o.status, short: false),
              IconButton(
                key: const Key('poRefresh'),
                tooltip: t.refresh,
                onPressed: _busy ? null : () => _load(quiet: true),
                icon: const Icon(Icons.refresh_rounded),
              ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.lg),
        OrderProgress(o.status),
        const SizedBox(height: AppSpacing.lg),
        if (o.overdue) ...[
          NoticeBox.warning(key: const Key('poOverdue'), t.overdueExplain),
          const SizedBox(height: AppSpacing.md),
        ],
        if (!o.supplierActive) ...[
          NoticeBox.warning(t.supplierInactiveBanner),
          const SizedBox(height: AppSpacing.md),
        ],
        if (o.status.isFinal) ...[
          NoticeBox(
            key: const Key('poFinal'),
            text: t.readOnlyFinal,
            icon: Icons.lock_outline_rounded,
          ),
          const SizedBox(height: AppSpacing.md),
        ],
        if (_notice != null) ...[
          NoticeBox(key: const Key('poNotice'), text: _notice!),
          const SizedBox(height: AppSpacing.md),
        ],
        if (_actionError != null) ...[
          ApiErrorBox(
            _actionError!,
            currentUserId: widget.currentUserId,
            action: _pendingCopyId != null
                ? () => _reportCopy(o.orderId)
                : (_unconfirmed != null && !_busy ? _checkAgain : null),
            actionLabel: _pendingCopyId == null && _unconfirmed != null
                ? t.checkAgain
                : null,
          ),
          const SizedBox(height: AppSpacing.md),
        ],
        _actions(t, o),
        const SizedBox(height: AppSpacing.lg),
        if (o.approvalBlockers.isNotEmpty) ...[
          SectionCard(
            key: const Key('poBlockers'),
            title: t.approvalBlockersTitle,
            icon: Icons.block_rounded,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final b in o.approvalBlockers) Text('• ${t.blocker(b)}'),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
        ],
        if (o.messageLength != null) ...[
          _lengthCard(t, o),
          const SizedBox(height: AppSpacing.lg),
        ],
        if (o.message != null) ...[
          _messageCard(t, o),
          const SizedBox(height: AppSpacing.lg),
        ],
        _communicationCard(t, o),
        const SizedBox(height: AppSpacing.lg),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SegmentedButton<int>(
                  key: const Key('poTabs'),
                  showSelectedIcon: false,
                  segments: [
                    ButtonSegment(
                      value: 0,
                      label: Text('${t.tabItems} (${o.items.length})'),
                    ),
                    ButtonSegment(
                      value: 1,
                      label: Text('${t.tabHistory} (${_events.length})'),
                    ),
                  ],
                  selected: {_tab},
                  onSelectionChanged: (v) => setState(() => _tab = v.first),
                ),
                const SizedBox(height: AppSpacing.lg),
                if (_tab == 0) _items(t, o) else _history(t),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _actions(PurchaseOrderStrings t, PurchaseOrder o) {
    final busy = _busy;
    final buttons = <Widget>[
      if (o.can('EDIT'))
        OutlinedButton.icon(
          key: const Key('poEdit'),
          onPressed: busy ? null : () => widget.onEdit(o.orderId),
          icon: const Icon(Icons.edit_outlined, size: 18),
          label: Text(t.actEdit),
        ),
      if (o.can('APPROVE'))
        FilledButton.icon(
          key: const Key('poApprove'),
          style: FilledButton.styleFrom(backgroundColor: AppColors.success),
          onPressed: busy || o.approvalBlockers.isNotEmpty
              ? null
              : () => _approve(o),
          icon: const Icon(Icons.verified_outlined, size: 18),
          label: Text(t.actApprove),
        ),
      if (o.can('REJECT'))
        OutlinedButton.icon(
          key: const Key('poReject'),
          onPressed: busy ? null : () => _reject(o),
          icon: const Icon(Icons.block_rounded, size: 18),
          label: Text(t.actReject),
        ),
      if (o.can('GENERATE_MESSAGE'))
        FilledButton.icon(
          key: const Key('poPrepare'),
          onPressed: busy ? null : () => _prepare(o),
          icon: const Icon(Icons.edit_note_rounded, size: 18),
          label: Text(t.actPrepare),
        ),
      // first opening: a main action; after SENT / CONFIRMED only under "More" (P4-D4)
      if (o.can('REQUEST_WHATSAPP_OPEN') &&
          o.status == PurchaseOrderStatus.messageGenerated)
        FilledButton.icon(
          key: const Key('poOpenWhatsapp'),
          style: FilledButton.styleFrom(backgroundColor: AppColors.success),
          onPressed: busy ? null : () => _open(o),
          icon: const Icon(Icons.chat_rounded, size: 18),
          label: Text(t.actOpen),
        ),
      if (o.can('COPY_MESSAGE'))
        OutlinedButton.icon(
          key: const Key('poCopy'),
          onPressed: busy ? null : () => _copy(o),
          icon: const Icon(Icons.copy_rounded, size: 18),
          label: Text(t.actCopy),
        ),
      if (o.can('MARK_SENT'))
        FilledButton.icon(
          key: const Key('poMarkSent'),
          onPressed: busy ? null : () => _markSent(o),
          icon: const Icon(Icons.outgoing_mail, size: 18),
          label: Text(t.actMarkSent),
        ),
      if (o.can('RECORD_SUPPLIER_CONFIRMATION'))
        FilledButton.icon(
          key: const Key('poConfirmSupplier'),
          style: FilledButton.styleFrom(backgroundColor: AppColors.success),
          onPressed: busy ? null : () => _confirm(o),
          icon: const Icon(Icons.handshake_outlined, size: 18),
          label: Text(t.actConfirm),
        ),
      if (o.can('CLOSE'))
        FilledButton.icon(
          key: const Key('poClose'),
          onPressed: busy ? null : () => _close(o),
          icon: const Icon(Icons.lock_outline_rounded, size: 18),
          label: Text(t.actClose),
        ),
      if (o.can('RECORD_SUPPLIER_CANCELLATION'))
        FilledButton.icon(
          key: const Key('poSupplierCancel'),
          onPressed: busy ? null : () => _supplierCancel(o),
          icon: const Icon(Icons.handshake_outlined, size: 18),
          label: Text(t.actSupplierCancel),
        ),
      if (o.can('CANCEL') || o.can('CANCEL_AFTER_CONTACT'))
        OutlinedButton.icon(
          key: const Key('poCancel'),
          style: OutlinedButton.styleFrom(foregroundColor: AppColors.danger),
          onPressed: busy ? null : () => _cancel(o),
          icon: const Icon(Icons.cancel_outlined, size: 18),
          label: Text(t.actCancel),
        ),
      if (o.can('REQUEST_WHATSAPP_OPEN') &&
          o.status != PurchaseOrderStatus.messageGenerated)
        PopupMenuButton<String>(
          key: const Key('poMore'),
          tooltip: t.actMore,
          enabled: !busy,
          onSelected: (_) => _open(o),
          itemBuilder: (_) => [
            PopupMenuItem(
              key: const Key('poOpenAgain'),
              value: 'open',
              child: Text(t.actOpenAgain),
            ),
          ],
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.more_horiz_rounded, size: 18),
                const SizedBox(width: 4),
                Text(t.actMore),
              ],
            ),
          ),
        ),
    ];
    if (buttons.isEmpty) return const SizedBox.shrink();
    return Wrap(
      key: const Key('poActions'),
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      children: [
        ...buttons,
        if (busy)
          const Padding(
            padding: EdgeInsets.all(8),
            child: SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          ),
      ],
    );
  }

  Widget _lengthCard(PurchaseOrderStrings t, PurchaseOrder o) => SectionCard(
    key: const Key('poLength'),
    title: t.lengthTitle,
    icon: Icons.straighten_rounded,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final r in o.messageLength!.values) ...[
          LengthMeter(r),
          if (!r.alwaysFits && r.minLinesToRemove > 0) ...[
            const SizedBox(height: 4),
            Text(
              t.moveLines(r.minLinesToRemove),
              style: const TextStyle(fontSize: 12.5, color: AppColors.danger),
            ),
            for (final l in r.longestLines)
              Text(
                '• ${l.productName} (${l.length})',
                style: const TextStyle(fontSize: 12),
              ),
          ],
          const SizedBox(height: AppSpacing.sm),
        ],
        if (o.messageLength!.values.any((r) => r.alwaysFits))
          Text(
            t.fitsOk,
            style: const TextStyle(fontSize: 12.5, color: AppColors.success),
          ),
      ],
    ),
  );

  Widget _messageCard(PurchaseOrderStrings t, PurchaseOrder o) {
    final m = o.message!;
    return SectionCard(
      key: const Key('poMessage'),
      title: t.messageTitle,
      icon: Icons.chat_outlined,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            t.savedDestination,
            style: const TextStyle(
              fontSize: 12.5,
              color: AppColors.textSecondary,
            ),
          ),
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: WhatsappNumber(m.whatsappNumber, large: true),
          ),
          if (m.numberChangedSinceGeneration) ...[
            const SizedBox(height: AppSpacing.sm),
            NoticeBox.danger(
              key: const Key('poNumberChanged'),
              t.numberChanged(m.whatsappNumber, m.currentSupplierWhatsapp),
            ),
          ],
          const SizedBox(height: AppSpacing.sm),
          MessageTextBox(text: m.text, language: m.language),
          const SizedBox(height: 4),
          Text(
            '${t.preparedBy(m.generatedBy?.label ?? '—', shortTime(m.generatedAt))} · ${t.lengthOf(m.length, m.maxLength)}',
            style: const TextStyle(
              fontSize: 12,
              color: AppColors.textSecondary,
            ),
          ),
          if (!m.actionable) ...[
            const SizedBox(height: AppSpacing.sm),
            NoticeBox.warning(
              key: const Key('poMessageReadOnly'),
              t.messageReadOnly,
            ),
          ],
        ],
      ),
    );
  }

  Widget _communicationCard(PurchaseOrderStrings t, PurchaseOrder o) {
    final c = o.communication;
    final lines = <Widget>[];
    void line(
      String text, {
      Color? color,
      IconData icon = Icons.circle,
      Key? key,
    }) => lines.add(
      Padding(
        key: key,
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 3),
              child: Icon(icon, size: 12, color: color ?? AppColors.textMuted),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                text,
                style: TextStyle(fontSize: 13, color: color, height: 1.4),
              ),
            ),
          ],
        ),
      ),
    );
    if (o.approvedBy != null) {
      line(
        '${t.approvedLabel}: ${t.by(o.approvedBy!.label, shortTime(o.approvedAt))}',
      );
    }
    if (o.rejectedBy != null) {
      line(
        '${t.rejectedLabel}: ${t.by(o.rejectedBy!.label, shortTime(o.rejectedAt))} — ${o.rejectionReason ?? ''}',
        color: AppColors.danger,
      );
    }
    if (o.message != null || c.copyReports + c.openRequests > 0) {
      line('${t.copies(c.copyReports)} — ${t.notVerified}');
      line(
        '${t.opens(c.openRequests, c.clientOpenReports)} — ${t.notVerified}',
      );
    }
    if (o.message != null) {
      line(
        c.sentRecordedByManager
            ? t.sentRecorded(
                c.sentMarkedBy?.label ?? '—',
                shortTime(c.sentMarkedAt),
              )
            : t.sentNotRecorded,
        key: const Key('poCommSent'),
        color: c.sentRecordedByManager ? AppColors.info : AppColors.warning,
      );
    }
    if (c.confirmationRecordedAt != null) {
      line(
        '${t.confirmationRecorded(c.confirmationRecordedBy?.label ?? '—', shortTime(c.confirmationRecordedAt))}'
        '${c.confirmationNote == null ? '' : ' — ${c.confirmationNote}'}',
        color: AppColors.success,
      );
    }
    if (c.closedAt != null) {
      line(t.closedRecorded(c.closedBy?.label ?? '—', shortTime(c.closedAt)));
    }
    if (o.status == PurchaseOrderStatus.canceled) {
      line(
        '${t.canceledLabel}: ${t.by(o.canceledBy?.label ?? '—', shortTime(o.canceledAt))} — ${o.cancelReason ?? ''}',
        color: AppColors.danger,
      );
      line(
        t.contactAtCancel(c.contactStatusAtCancel),
        key: const Key('poContactAtCancel'),
        color: AppColors.danger,
      );
      line(t.cancelNotTold);
      if (c.possibleSupplierContact) {
        line(
          c.supplierCancellationRecorded
              ? t.supplierAcceptanceRecorded
              : t.supplierAcceptanceMissing,
          key: const Key('poSupplierAcceptance'),
          color: c.supplierCancellationRecorded
              ? AppColors.success
              : AppColors.warning,
        );
      }
    }
    if (lines.isEmpty) return const SizedBox.shrink();
    return SectionCard(
      key: const Key('poCommunication'),
      title: t.communicationTitle,
      icon: Icons.forum_outlined,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: lines,
      ),
    );
  }

  Widget _items(PurchaseOrderStrings t, PurchaseOrder o) {
    const header = TextStyle(
      fontSize: 12,
      fontWeight: FontWeight.w700,
      color: AppColors.textSecondary,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        MinWidthScroll(
          minWidth: 900,
          child: Column(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 10,
                ),
                decoration: BoxDecoration(
                  color: AppColors.background,
                  borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
                ),
                child: Row(
                  children: [
                    Expanded(flex: 4, child: Text(t.colProduct, style: header)),
                    Expanded(flex: 3, child: Text(t.colBarcode, style: header)),
                    Expanded(
                      flex: 2,
                      child: Text(t.colQuantity, style: header),
                    ),
                    Expanded(
                      flex: 2,
                      child: Text(t.colSuggested, style: header),
                    ),
                    Expanded(flex: 2, child: Text(t.colBasis, style: header)),
                    Expanded(
                      flex: 3,
                      child: Text(t.colUnitCost, style: header),
                    ),
                  ],
                ),
              ),
              for (final i in o.items)
                Container(
                  key: Key('po-item-${i.itemId}'),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 9,
                  ),
                  decoration: const BoxDecoration(
                    border: Border(
                      bottom: BorderSide(color: AppColors.divider),
                    ),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        flex: 4,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              i.productName,
                              style: const TextStyle(
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                            Wrap(
                              spacing: 6,
                              children: [
                                if (i.reserved)
                                  Text(
                                    t.reserved,
                                    style: const TextStyle(
                                      fontSize: 11.5,
                                      color: AppColors.info,
                                    ),
                                  ),
                                if (i.productDeleted)
                                  Text(
                                    t.productDeleted,
                                    style: const TextStyle(
                                      fontSize: 11.5,
                                      color: AppColors.danger,
                                    ),
                                  ),
                              ],
                            ),
                          ],
                        ),
                      ),
                      Expanded(flex: 3, child: LtrText(i.barcode)),
                      Expanded(
                        flex: 2,
                        child: Text(
                          '${i.quantity}',
                          style: const TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ),
                      Expanded(
                        flex: 2,
                        child: Text(
                          i.suggestedQuantity == null
                              ? '—'
                              : '${i.suggestedQuantity}',
                        ),
                      ),
                      Expanded(flex: 2, child: Text(t.basis(i.basis))),
                      Expanded(
                        flex: 3,
                        child: Text(
                          i.expectedUnitCost == null
                              ? t.unknownCost
                              : Formatters.currency(i.expectedUnitCost!),
                          style: const TextStyle(fontSize: 12.5),
                        ),
                      ),
                    ],
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.md),
        Text(
          '${t.totalExpected}: ${o.expectedTotalCost == null ? t.unknownCost : Formatters.currency(o.expectedTotalCost!)}',
          style: const TextStyle(fontWeight: FontWeight.w700),
        ),
        Text(
          t.noCostInMessage,
          key: const Key('poCostNote'),
          style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
        ),
        if ((o.note ?? '').isNotEmpty) ...[
          const SizedBox(height: AppSpacing.md),
          Text(
            '${t.noteTitle}: ${o.note}',
            style: const TextStyle(fontSize: 13),
          ),
          Text(
            t.noteHelp,
            style: const TextStyle(
              fontSize: 12,
              color: AppColors.textSecondary,
            ),
          ),
        ],
      ],
    );
  }

  Widget _history(PurchaseOrderStrings t) {
    if (_events.isEmpty) return Text(t.noHistory);
    return Column(
      key: const Key('poHistory'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final e in _events.reversed)
          Container(
            key: Key('po-event-${e.eventId}'),
            padding: const EdgeInsets.symmetric(vertical: 8),
            decoration: const BoxDecoration(
              border: Border(bottom: BorderSide(color: AppColors.divider)),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(
                  width: 150,
                  child: LtrText(
                    shortTime(e.createdAt),
                    style: const TextStyle(
                      fontSize: 12.5,
                      color: AppColors.textSecondary,
                    ),
                  ),
                ),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        t.event(e),
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                      if (t.eventDetail(e).isNotEmpty)
                        Text(
                          t.eventDetail(e),
                          style: const TextStyle(
                            fontSize: 12.5,
                            color: AppColors.textSecondary,
                          ),
                        ),
                    ],
                  ),
                ),
                SizedBox(
                  width: 160,
                  child: Text(
                    e.user?.label ?? '—',
                    textAlign: TextAlign.end,
                    style: const TextStyle(fontSize: 12.5),
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }
}
