import 'package:flutter/material.dart';

import '../../l10n/purchase_order_strings.dart';
import '../../models/purchase_order.dart';
import '../../services/api_http.dart';
import '../../services/purchase_api_client.dart';
import '../../services/url_opener.dart';
import '../../theme/app_theme.dart';
import 'purchase_widgets.dart';

/// Dialogs of the order details. The confirmation dialogs only collect the
/// manager's explicit answer; the details page calls the API with it (one
/// place for error handling). The preview and WhatsApp dialogs talk to the
/// API themselves because they have several steps.

Widget _dialogFrame({
  required BuildContext context,
  required String title,
  required List<Widget> children,
  required List<Widget> actions,
  IconData icon = Icons.help_outline_rounded,
  Color color = AppColors.primary,
  double width = 520,
  Key? key,
}) => AlertDialog(
  key: key,
  icon: Icon(icon, color: color, size: 34),
  title: Text(title, textAlign: TextAlign.center),
  content: SizedBox(
    width: width,
    child: SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: children,
      ),
    ),
  ),
  actionsAlignment: MainAxisAlignment.end,
  actions: actions,
);

bool _reasonOk(String v) {
  final n = v.trim().runes.length;
  return n >= 3 && n <= 300;
}

// ── reject ─────────────────────────────────────────────────────────────────

Future<String?> showRejectDialog(BuildContext context) =>
    showDialog<String>(context: context, builder: (_) => const _ReasonDialog());

class _ReasonDialog extends StatefulWidget {
  const _ReasonDialog();

  @override
  State<_ReasonDialog> createState() => _ReasonDialogState();
}

class _ReasonDialogState extends State<_ReasonDialog> {
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    return _dialogFrame(
      key: const Key('rejectDialog'),
      context: context,
      title: t.rejectTitle,
      icon: Icons.block_rounded,
      color: AppColors.danger,
      children: [
        TextField(
          key: const Key('rejectReason'),
          controller: _reason,
          maxLines: 3,
          onChanged: (_) => setState(() {}),
          decoration: InputDecoration(
            labelText: t.reasonLabel,
            errorText: _reason.text.isEmpty || _reasonOk(_reason.text)
                ? null
                : t.reasonError,
          ),
        ),
      ],
      actions: [
        OutlinedButton(
          onPressed: () => Navigator.pop(context),
          child: Text(t.cancel),
        ),
        FilledButton(
          key: const Key('rejectConfirm'),
          style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
          onPressed: _reasonOk(_reason.text)
              ? () => Navigator.pop(context, _reason.text.trim())
              : null,
          child: Text(t.actReject),
        ),
      ],
    );
  }
}

// ── cancel ─────────────────────────────────────────────────────────────────

/// The manager's answer in the cancel dialog. [recordSendingFirst]: the
/// manager said the message WAS sent — nothing is canceled, the sending must
/// be recorded first.
class CancelChoice {
  const CancelChoice({
    required this.reason,
    this.contactStatus,
    this.confirmNotSent,
    this.acknowledgeSupplierNotNotified,
    this.recordSendingFirst = false,
  });

  final String reason;
  final ContactStatus? contactStatus;
  final bool? confirmNotSent;
  final bool? acknowledgeSupplierNotNotified;
  final bool recordSendingFirst;
}

Future<CancelChoice?> showCancelDialog(
  BuildContext context,
  PurchaseOrder order,
) => showDialog<CancelChoice>(
  context: context,
  builder: (_) => _CancelDialog(order: order),
);

class _CancelDialog extends StatefulWidget {
  const _CancelDialog({required this.order});

  final PurchaseOrder order;

  @override
  State<_CancelDialog> createState() => _CancelDialogState();
}

class _CancelDialogState extends State<_CancelDialog> {
  final _reason = TextEditingController();
  ContactStatus? _contact; // never preselected
  bool _confirmNotSent = false;
  bool _ackNotNotified = false;

  PurchaseOrderStatus get _status => widget.order.status;
  bool get _afterMessage => _status == PurchaseOrderStatus.messageGenerated;
  bool get _afterContact =>
      _status == PurchaseOrderStatus.sent ||
      _status == PurchaseOrderStatus.confirmed;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  bool get _ready {
    if (_afterMessage) {
      if (_contact == null) return false;
      if (_contact == ContactStatus.sent)
        return true; // goes to "record sending"
      if (_contact == ContactStatus.notSent && !_confirmNotSent) return false;
    }
    if (_afterContact && !_ackNotNotified) return false;
    return _reasonOk(_reason.text);
  }

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    final c = widget.order.communication;
    Widget option(ContactStatus v, String label) =>
        RadioListTile<ContactStatus>(
          key: Key('cancel-${v.code}'),
          value: v,
          dense: true,
          contentPadding: EdgeInsets.zero,
          title: Text(
            label,
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
        );
    final sentChosen = _afterMessage && _contact == ContactStatus.sent;
    return _dialogFrame(
      key: const Key('cancelDialog'),
      context: context,
      title: t.cancelTitle,
      icon: Icons.cancel_outlined,
      color: AppColors.danger,
      width: 560,
      children: [
        if (!_afterMessage && !_afterContact)
          NoticeBox(text: t.cancelBeforeMessage),
        if (_afterMessage) ...[
          Text(
            t.wasItSent,
            style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15),
          ),
          const SizedBox(height: 4),
          Text(
            t.wasItSentHelp,
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 13,
            ),
          ),
          RadioGroup<ContactStatus>(
            groupValue: _contact,
            onChanged: (v) => setState(() {
              _contact = v;
              _confirmNotSent = false;
            }),
            child: Column(
              children: [
                option(ContactStatus.notSent, t.optNotSent),
                option(ContactStatus.unknown, t.optUnknown),
                option(ContactStatus.sent, t.optSent),
              ],
            ),
          ),
          if (_contact == ContactStatus.notSent) ...[
            if (c.copyReports + c.openRequests > 0)
              NoticeBox.warning(t.priorActions(c.copyReports, c.openRequests)),
            CheckboxListTile(
              key: const Key('cancelConfirmNotSent'),
              value: _confirmNotSent,
              dense: true,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              onChanged: (v) => setState(() => _confirmNotSent = v ?? false),
              title: Text(t.confirmNotSentLabel),
            ),
          ],
          if (_contact == ContactStatus.unknown)
            NoticeBox.warning(
              key: const Key('cancelUnknownExplain'),
              t.unknownExplain,
            ),
          if (sentChosen)
            NoticeBox(key: const Key('cancelSentExplain'), text: t.sentExplain),
        ],
        if (_afterContact) ...[
          NoticeBox.danger(
            key: const Key('cancelAfterContactWarning'),
            t.afterContactWarning,
          ),
          CheckboxListTile(
            key: const Key('cancelAckNotNotified'),
            value: _ackNotNotified,
            dense: true,
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            onChanged: (v) => setState(() => _ackNotNotified = v ?? false),
            title: Text(t.ackNotNotifiedLabel),
          ),
        ],
        if (!sentChosen) ...[
          const SizedBox(height: AppSpacing.sm),
          TextField(
            key: const Key('cancelReason'),
            controller: _reason,
            maxLines: 2,
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(
              labelText: t.reasonLabel,
              errorText: _reason.text.isEmpty || _reasonOk(_reason.text)
                  ? null
                  : t.reasonError,
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            t.cancelNotTold,
            style: const TextStyle(
              fontSize: 12,
              color: AppColors.textSecondary,
            ),
          ),
        ],
      ],
      actions: [
        OutlinedButton(
          onPressed: () => Navigator.pop(context),
          child: Text(t.keepOrder),
        ),
        if (sentChosen)
          FilledButton(
            key: const Key('cancelGoRecordSending'),
            onPressed: () => Navigator.pop(
              context,
              const CancelChoice(reason: '', recordSendingFirst: true),
            ),
            child: Text(t.goRecordSending),
          )
        else
          FilledButton(
            key: const Key('cancelConfirm'),
            style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
            onPressed: _ready
                ? () => Navigator.pop(
                    context,
                    CancelChoice(
                      reason: _reason.text.trim(),
                      contactStatus: _afterMessage ? _contact : null,
                      confirmNotSent:
                          _afterMessage && _contact == ContactStatus.notSent
                          ? true
                          : null,
                      acknowledgeSupplierNotNotified: _afterContact
                          ? true
                          : null,
                    ),
                  )
                : null,
            child: Text(t.confirmCancel),
          ),
      ],
    );
  }
}

// ── confirmations with a required checkbox ─────────────────────────────────

/// A confirmation that needs a ticked checkbox (and optionally a note).
Future<(bool, String?)?> showCheckDialog(
  BuildContext context, {
  required Key key,
  required String title,
  required String check,
  required String confirmLabel,
  String? body,
  String? warning,
  String? noteLabel,
  int noteMax = 300,
  IconData icon = Icons.task_alt_rounded,
  Color color = AppColors.primary,
}) => showDialog<(bool, String?)>(
  context: context,
  builder: (_) => _CheckDialog(
    dialogKey: key,
    title: title,
    check: check,
    confirmLabel: confirmLabel,
    body: body,
    warning: warning,
    noteLabel: noteLabel,
    noteMax: noteMax,
    icon: icon,
    color: color,
  ),
);

class _CheckDialog extends StatefulWidget {
  const _CheckDialog({
    required this.dialogKey,
    required this.title,
    required this.check,
    required this.confirmLabel,
    this.body,
    this.warning,
    this.noteLabel,
    required this.noteMax,
    required this.icon,
    required this.color,
  });

  final Key dialogKey;
  final String title;
  final String check;
  final String confirmLabel;
  final String? body;
  final String? warning;
  final String? noteLabel;
  final int noteMax;
  final IconData icon;
  final Color color;

  @override
  State<_CheckDialog> createState() => _CheckDialogState();
}

class _CheckDialogState extends State<_CheckDialog> {
  bool _checked = false;
  final _note = TextEditingController();

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  bool get _noteOk => _note.text.trim().runes.length <= widget.noteMax;

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    return _dialogFrame(
      key: widget.dialogKey,
      context: context,
      title: widget.title,
      icon: widget.icon,
      color: widget.color,
      children: [
        if (widget.body != null)
          Text(
            widget.body!,
            style: const TextStyle(
              color: AppColors.textSecondary,
              height: 1.45,
            ),
          ),
        if (widget.warning != null) ...[
          const SizedBox(height: AppSpacing.sm),
          NoticeBox.warning(widget.warning!),
        ],
        CheckboxListTile(
          key: const Key('checkDialogBox'),
          value: _checked,
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
          onChanged: (v) => setState(() => _checked = v ?? false),
          title: Text(
            widget.check,
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
        ),
        if (widget.noteLabel != null)
          TextField(
            key: const Key('checkDialogNote'),
            controller: _note,
            maxLines: 2,
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(
              labelText: widget.noteLabel,
              errorText: _noteOk ? null : t.confirmNoteError,
            ),
          ),
      ],
      actions: [
        OutlinedButton(
          onPressed: () => Navigator.pop(context),
          child: Text(t.cancel),
        ),
        FilledButton(
          key: const Key('checkDialogConfirm'),
          style: FilledButton.styleFrom(backgroundColor: widget.color),
          onPressed: _checked && _noteOk
              ? () => Navigator.pop(context, (
                  true,
                  _note.text.trim().isEmpty ? null : _note.text.trim(),
                ))
              : null,
          child: Text(widget.confirmLabel),
        ),
      ],
    );
  }
}

// ── supplier acceptance of a cancellation ─────────────────────────────────

Future<(String, String)?> showSupplierCancellationDialog(
  BuildContext context,
) => showDialog<(String, String)>(
  context: context,
  builder: (_) => const _SupplierCancelDialog(),
);

class _SupplierCancelDialog extends StatefulWidget {
  const _SupplierCancelDialog();

  @override
  State<_SupplierCancelDialog> createState() => _SupplierCancelDialogState();
}

class _SupplierCancelDialogState extends State<_SupplierCancelDialog> {
  bool _checked = false;
  String? _channel;
  final _note = TextEditingController();

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    return _dialogFrame(
      key: const Key('supplierCancelDialog'),
      context: context,
      title: t.supplierCancelTitle,
      icon: Icons.handshake_outlined,
      color: AppColors.warning,
      children: [
        NoticeBox.warning(t.supplierCancelWarning),
        CheckboxListTile(
          key: const Key('supplierCancelCheck'),
          value: _checked,
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
          onChanged: (v) => setState(() => _checked = v ?? false),
          title: Text(
            t.supplierCancelCheck,
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
        ),
        DropdownButtonFormField<String>(
          key: const Key('supplierCancelChannel'),
          initialValue: _channel,
          decoration: InputDecoration(labelText: t.channelLabel),
          items: [
            for (final c in const ['PHONE', 'WHATSAPP', 'IN_PERSON', 'OTHER'])
              DropdownMenuItem(value: c, child: Text(t.channel(c))),
          ],
          onChanged: (v) => setState(() => _channel = v),
        ),
        const SizedBox(height: AppSpacing.sm),
        TextField(
          key: const Key('supplierCancelNote'),
          controller: _note,
          maxLines: 2,
          onChanged: (_) => setState(() {}),
          decoration: InputDecoration(
            labelText: t.supplierCancelNote,
            errorText: _note.text.isEmpty || _reasonOk(_note.text)
                ? null
                : t.reasonError,
          ),
        ),
      ],
      actions: [
        OutlinedButton(
          onPressed: () => Navigator.pop(context),
          child: Text(t.cancel),
        ),
        FilledButton(
          key: const Key('supplierCancelConfirm'),
          onPressed: _checked && _channel != null && _reasonOk(_note.text)
              ? () => Navigator.pop(context, (_channel!, _note.text.trim()))
              : null,
          child: Text(t.recordAcceptance),
        ),
      ],
    );
  }
}

// ── preview and prepare the message ───────────────────────────────────────

/// Preview (Arabic by default / English) and prepare the message. Returns the
/// order after the message was prepared, or null.
Future<PurchaseOrder?> showPreviewDialog(
  BuildContext context, {
  required PurchaseApi api,
  required PurchaseOrder order,
  VoidCallback? onOpenSuppliers,
}) => showDialog<PurchaseOrder>(
  context: context,
  barrierDismissible: false,
  builder: (_) =>
      _PreviewDialog(api: api, order: order, onOpenSuppliers: onOpenSuppliers),
);

class _PreviewDialog extends StatefulWidget {
  const _PreviewDialog({
    required this.api,
    required this.order,
    this.onOpenSuppliers,
  });

  final PurchaseApi api;
  final PurchaseOrder order;
  final VoidCallback? onOpenSuppliers;

  @override
  State<_PreviewDialog> createState() => _PreviewDialogState();
}

class _PreviewDialogState extends State<_PreviewDialog> {
  String _language = 'ar';
  MessagePreview? _preview;
  ApiException? _error;
  bool _loading = true;
  bool _saving = false;
  bool _changed = false;

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
      final p = await widget.api.preview(widget.order.orderId, _language);
      if (!mounted) return;
      setState(() {
        _preview = p;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _preview = null;
        _error = e;
        _loading = false;
      });
    }
  }

  Future<void> _prepare() async {
    final p = _preview!;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final order = await widget.api.generateMessage(
        widget.order.orderId,
        version: widget.order.version,
        language: p.language,
        previewToken: p.previewToken,
      );
      if (mounted) Navigator.pop(context, order);
    } on ApiException catch (e) {
      if (!mounted) return;
      final fresh = e.detailsMap['preview'];
      setState(() {
        _saving = false;
        _error = e;
        if (e.code == 'PREVIEW_CHANGED' && fresh is Map<String, dynamic>) {
          _preview = MessagePreview.fromJson(
            fresh,
          ); // show the new text; nothing is saved automatically
          _changed = true;
        }
      });
      // other errors (stale version, unknown outcome): the details page reloads after closing
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    final p = _preview;
    final tooLong = _error?.code == 'MESSAGE_TOO_LONG'
        ? MessageLength.mapFromJson(_error!.detailsMap['messageLength'])
        : null;
    return _dialogFrame(
      key: const Key('previewDialog'),
      context: context,
      title: t.previewTitle,
      icon: Icons.edit_note_rounded,
      width: 640,
      children: [
        NoticeBox(text: t.previewOnly),
        const SizedBox(height: AppSpacing.md),
        Row(
          children: [
            Text('${t.languageLabel}: '),
            const SizedBox(width: 8),
            SegmentedButton<String>(
              key: const Key('previewLanguage'),
              showSelectedIcon: false,
              segments: [
                ButtonSegment(value: 'ar', label: Text(t.arabic)),
                ButtonSegment(value: 'en', label: Text(t.english)),
              ],
              selected: {_language},
              onSelectionChanged: _saving
                  ? null
                  : (v) {
                      setState(() {
                        _language = v.first;
                        _changed = false;
                      });
                      _load();
                    },
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        if (_loading)
          const Center(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: CircularProgressIndicator(),
            ),
          ),
        if (p != null && !_loading) ...[
          Text(
            t.toNumber,
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 12.5,
            ),
          ),
          WhatsappNumber(p.whatsappNumber, large: true),
          Text(
            p.supplierName,
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: AppSpacing.sm),
          MessageTextBox(text: p.text, language: p.language),
          const SizedBox(height: 4),
          Text(
            t.lengthOf(p.length.length, p.length.limit),
            style: const TextStyle(
              fontSize: 12,
              color: AppColors.textSecondary,
            ),
          ),
        ],
        if (_changed) ...[
          const SizedBox(height: AppSpacing.sm),
          NoticeBox.warning(key: const Key('previewChanged'), t.previewChanged),
        ],
        if (_error != null && !_changed) ...[
          const SizedBox(height: AppSpacing.sm),
          ApiErrorBox(_error!),
          if (tooLong != null)
            for (final r in tooLong.values)
              if (!r.fits) ...[
                const SizedBox(height: 6),
                LengthMeter(r),
                if (r.minLinesToRemove > 0)
                  Text(
                    t.moveLines(r.minLinesToRemove),
                    style: const TextStyle(fontSize: 12),
                  ),
                for (final l in r.longestLines)
                  Text(
                    '• ${l.productName} (${l.length})',
                    style: const TextStyle(fontSize: 12),
                  ),
              ],
          if (_error!.code == 'SUPPLIER_NO_WHATSAPP' &&
              widget.onOpenSuppliers != null)
            TextButton(
              onPressed: () {
                Navigator.pop(context);
                widget.onOpenSuppliers!();
              },
              child: Text(t.goSuppliers),
            ),
        ],
      ],
      actions: [
        OutlinedButton(
          key: const Key('previewClose'),
          onPressed: _saving ? null : () => Navigator.pop(context),
          child: Text(t.close),
        ),
        FilledButton.icon(
          key: const Key('previewPrepare'),
          onPressed: p != null && !_loading && !_saving ? _prepare : null,
          icon: const Icon(Icons.save_rounded, size: 18),
          label: Text(t.prepareAndSave),
        ),
      ],
    );
  }
}

// ── open WhatsApp: two steps ───────────────────────────────────────────────

/// Step 1: the manager checks the saved number and the full message and
/// confirms → the opening request is recorded and the backend returns the
/// link. Step 2: a separate "Open WhatsApp Now" click opens it (a direct
/// click, so browsers do not block the new window). The client-observed
/// result is reported. Nothing here records sending.
Future<void> showOpenWhatsappDialog(
  BuildContext context, {
  required PurchaseApi api,
  required UrlOpener opener,
  required PurchaseOrder order,
}) => showDialog<void>(
  context: context,
  barrierDismissible: false,
  builder: (_) => _OpenDialog(api: api, opener: opener, order: order),
);

class _OpenDialog extends StatefulWidget {
  const _OpenDialog({
    required this.api,
    required this.opener,
    required this.order,
  });

  final PurchaseApi api;
  final UrlOpener opener;
  final PurchaseOrder order;

  @override
  State<_OpenDialog> createState() => _OpenDialogState();
}

class _OpenDialogState extends State<_OpenDialog> {
  // one action id for this dialog: retries are recorded once
  final String _actionId = newClientId();
  bool _reviewed = false;
  bool _changedAck = false;
  bool _againAck = false;
  bool _busy = false;
  String? _url;
  bool? _opened;
  bool _resultSaved = true;
  ApiException? _error;

  SupplierMessage get _m => widget.order.message!;
  bool get _again =>
      widget.order.status == PurchaseOrderStatus.sent ||
      widget.order.status == PurchaseOrderStatus.confirmed;

  bool get _canConfirm =>
      _reviewed &&
      (!_m.numberChangedSinceGeneration || _changedAck) &&
      (!_again || _againAck) &&
      !_busy;

  Future<void> _confirm() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final r = await widget.api.openRequested(widget.order.orderId, _actionId);
      if (!mounted) return;
      setState(() {
        _busy = false;
        _url = r.whatsappUrl;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = e; // retry uses the same action id: never two records
      });
    }
  }

  Future<void> _openNow() async {
    final url = _url!;
    setState(() => _busy = true);
    final ok = await widget.opener.open(url);
    var saved = true;
    try {
      await widget.api.openResult(widget.order.orderId, _actionId, ok);
    } on ApiException {
      saved = false; // the history entry is optional; the order is not affected
    }
    if (!mounted) return;
    setState(() {
      _busy = false;
      _opened = ok;
      _resultSaved = saved;
    });
  }

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    final m = _m;
    final c = widget.order.communication;
    return _dialogFrame(
      key: const Key('openWhatsappDialog'),
      context: context,
      title: t.openTitle,
      icon: Icons.chat_rounded,
      color: AppColors.success,
      width: 640,
      children: [
        Text(
          t.savedDestination,
          style: const TextStyle(
            color: AppColors.textSecondary,
            fontSize: 12.5,
          ),
        ),
        WhatsappNumber(m.whatsappNumber, large: true),
        Text(
          widget.order.supplierName,
          style: const TextStyle(fontWeight: FontWeight.w700),
        ),
        if (m.numberChangedSinceGeneration) ...[
          const SizedBox(height: AppSpacing.sm),
          NoticeBox.danger(
            key: const Key('openNumberChanged'),
            t.numberChanged(m.whatsappNumber, m.currentSupplierWhatsapp),
          ),
        ],
        if (_again) ...[
          const SizedBox(height: AppSpacing.sm),
          NoticeBox.danger(
            key: const Key('openAgainWarning'),
            t.againWarning(shortTime(c.sentMarkedAt), c.openRequests),
          ),
        ],
        const SizedBox(height: AppSpacing.sm),
        MessageTextBox(text: m.text, language: m.language),
        const SizedBox(height: AppSpacing.sm),
        if (_url == null) ...[
          Text(
            t.openStep1,
            style: const TextStyle(
              fontSize: 13,
              color: AppColors.textSecondary,
            ),
          ),
          CheckboxListTile(
            key: const Key('openReviewed'),
            value: _reviewed,
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            onChanged: _busy
                ? null
                : (v) => setState(() => _reviewed = v ?? false),
            title: Text(
              t.reviewedLabel,
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
          ),
          if (m.numberChangedSinceGeneration)
            CheckboxListTile(
              key: const Key('openChangedAck'),
              value: _changedAck,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              onChanged: _busy
                  ? null
                  : (v) => setState(() => _changedAck = v ?? false),
              title: Text(t.changedReviewedLabel),
            ),
          if (_again)
            CheckboxListTile(
              key: const Key('openAgainAck'),
              value: _againAck,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              onChanged: _busy
                  ? null
                  : (v) => setState(() => _againAck = v ?? false),
              title: Text(t.againAck),
            ),
        ] else ...[
          if (_url!.length > 2000)
            NoticeBox.warning(key: const Key('openLongLink'), t.longLink),
          if (_opened == null)
            NoticeBox(key: const Key('openReady'), text: t.openReady),
          if (_opened == true)
            NoticeBox(
              key: const Key('openDone'),
              text: t.opened,
              icon: Icons.check_circle_outline_rounded,
            ),
          if (_opened == false)
            NoticeBox.warning(
              key: const Key('openFailed'),
              t.notOpened(formatPlus(m.whatsappNumber)),
            ),
          if (_opened != null && !_resultSaved)
            Text(
              t.resultNotSaved,
              style: const TextStyle(
                fontSize: 12,
                color: AppColors.textSecondary,
              ),
            ),
        ],
        if (_error != null) ...[
          const SizedBox(height: AppSpacing.sm),
          ApiErrorBox(_error!),
        ],
      ],
      actions: [
        OutlinedButton(
          key: const Key('openClose'),
          onPressed: _busy ? null : () => Navigator.pop(context),
          child: Text(t.close),
        ),
        if (_url == null)
          FilledButton(
            key: const Key('openConfirm'),
            onPressed: _canConfirm ? _confirm : null,
            child: Text(t.confirmOpen),
          )
        else
          FilledButton.icon(
            key: const Key('openNow'),
            style: FilledButton.styleFrom(backgroundColor: AppColors.success),
            onPressed: _busy || _opened == true ? null : _openNow,
            icon: const Icon(Icons.open_in_new_rounded, size: 18),
            label: Text(t.openNow),
          ),
      ],
    );
  }
}

String formatPlus(String digits) => '+$digits';
