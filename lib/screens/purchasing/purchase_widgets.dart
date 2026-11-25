import 'package:flutter/material.dart';

import '../../l10n/purchase_order_strings.dart';
import '../../models/purchase_order.dart';
import '../../models/supplier.dart';
import '../../services/api_http.dart';
import '../../theme/app_theme.dart';

/// Colors of a purchase-order state.
(Color, Color, IconData) statusStyle(PurchaseOrderStatus s) => switch (s) {
  PurchaseOrderStatus.pendingApproval => (
    AppColors.warning,
    AppColors.warningSoft,
    Icons.hourglass_top_rounded,
  ),
  PurchaseOrderStatus.approved => (
    AppColors.info,
    AppColors.infoSoft,
    Icons.verified_outlined,
  ),
  PurchaseOrderStatus.messageGenerated => (
    AppColors.primary,
    AppColors.primarySoft,
    Icons.edit_note_rounded,
  ),
  PurchaseOrderStatus.sent => (
    AppColors.info,
    AppColors.infoSoft,
    Icons.outgoing_mail,
  ),
  PurchaseOrderStatus.confirmed => (
    AppColors.success,
    AppColors.successSoft,
    Icons.handshake_outlined,
  ),
  PurchaseOrderStatus.closed => (
    AppColors.textSecondary,
    AppColors.background,
    Icons.lock_outline_rounded,
  ),
  PurchaseOrderStatus.rejected => (
    AppColors.danger,
    AppColors.dangerSoft,
    Icons.block_rounded,
  ),
  PurchaseOrderStatus.canceled => (
    AppColors.danger,
    AppColors.dangerSoft,
    Icons.cancel_outlined,
  ),
};

class PurchaseStatusChip extends StatelessWidget {
  const PurchaseStatusChip(this.status, {super.key, this.short = true});

  final PurchaseOrderStatus status;
  final bool short;

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    final (color, soft, icon) = statusStyle(status);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: soft,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 15, color: color),
          const SizedBox(width: 5),
          Flexible(
            child: Text(
              short ? t.statusShort(status) : t.status(status),
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: color,
                fontWeight: FontWeight.w700,
                fontSize: 12.5,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// A value that always reads left-to-right (order numbers, barcodes, numbers),
/// placed at the start of its cell in both languages.
class LtrText extends StatelessWidget {
  const LtrText(this.text, {super.key, this.style});

  final String text;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) => Align(
    alignment: AlignmentDirectional.centerStart,
    child: Directionality(
      textDirection: TextDirection.ltr,
      child: Text(text, style: style, overflow: TextOverflow.ellipsis),
    ),
  );
}

/// A WhatsApp number as +970 599 …, always left-to-right.
class WhatsappNumber extends StatelessWidget {
  const WhatsappNumber(this.digits, {super.key, this.large = false});

  final String digits;
  final bool large;

  // Plain Text in a SelectionArea: readable by screen readers and selectable
  // with the mouse (SelectableText is an empty box for screen readers on the
  // web, and shrank to its content so it was misaligned at some widths).
  @override
  Widget build(BuildContext context) => Align(
    // at the START of the line in both languages (right side in Arabic)
    alignment: AlignmentDirectional.centerStart,
    widthFactor: 1, // in a Wrap / Row it takes only its own width
    child: SelectionArea(
      child: Text(
        formatWhatsapp(digits),
        textDirection: TextDirection.ltr,
        style: TextStyle(
          fontSize: large ? 22 : 14,
          fontWeight: FontWeight.w800,
          letterSpacing: large ? 0.6 : 0,
          color: AppColors.textPrimary,
        ),
      ),
    ),
  );
}

/// A colored notice box (info / warning / danger).
class NoticeBox extends StatelessWidget {
  const NoticeBox({
    super.key,
    required this.text,
    this.color = AppColors.info,
    this.soft = AppColors.infoSoft,
    this.icon = Icons.info_outline_rounded,
    this.child,
  });

  final String text;
  final Color color;
  final Color soft;
  final IconData icon;
  final Widget? child;

  factory NoticeBox.warning(String text, {Key? key, Widget? child}) =>
      NoticeBox(
        key: key,
        text: text,
        color: AppColors.warning,
        soft: AppColors.warningSoft,
        icon: Icons.warning_amber_rounded,
        child: child,
      );

  factory NoticeBox.danger(String text, {Key? key, Widget? child}) => NoticeBox(
    key: key,
    text: text,
    color: AppColors.danger,
    soft: AppColors.dangerSoft,
    icon: Icons.report_gmailerrorred_rounded,
    child: child,
  );

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(AppSpacing.md),
    decoration: BoxDecoration(
      color: soft,
      borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      border: Border.all(color: color.withValues(alpha: 0.35)),
    ),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, color: color, size: 20),
        const SizedBox(width: AppSpacing.sm),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(text, style: const TextStyle(height: 1.45, fontSize: 13.5)),
              if (child != null) ...[
                const SizedBox(height: AppSpacing.sm),
                // its own Material: ink of list tiles inside stays visible on the colored box
                Material(type: MaterialType.transparency, child: child!),
              ],
            ],
          ),
        ),
      ],
    ),
  );
}

/// An API error: a clear sentence for the code, the server text under
/// "Details", and an optional action (retry / reload).
class ApiErrorBox extends StatelessWidget {
  const ApiErrorBox(
    this.error, {
    super.key,
    this.action,
    this.actionLabel,
    this.extra,
    this.currentUserId,
  });

  final ApiException error;
  final VoidCallback? action;
  final String? actionLabel;
  final String? extra;

  /// The logged-in manager (D3: "your own earlier request" vs another one).
  final int? currentUserId;

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    return NoticeBox.danger(
      key: const Key('purchaseError'),
      [t.error(error, currentUserId: currentUserId), ?extra].join(' '),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${t.technicalDetails}: ${error.code ?? error.statusCode ?? ''} — ${error.message}',
            style: const TextStyle(
              fontSize: 11.5,
              color: AppColors.textSecondary,
            ),
          ),
          if (action != null) ...[
            const SizedBox(height: AppSpacing.sm),
            OutlinedButton.icon(
              key: const Key('purchaseErrorAction'),
              onPressed: action,
              icon: const Icon(Icons.refresh_rounded, size: 16),
              label: Text(actionLabel ?? t.retry),
            ),
          ],
        ],
      ),
    );
  }
}

/// One reorder caution (a recent order that may still bring the product).
class CautionTile extends StatelessWidget {
  const CautionTile(this.caution, {super.key, this.trailing});

  final PurchaseCaution caution;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    return Container(
      margin: const EdgeInsets.only(top: 4),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: AppColors.warningSoft,
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
      ),
      child: Row(
        children: [
          const Icon(
            Icons.history_toggle_off_rounded,
            size: 16,
            color: AppColors.warning,
          ),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              '${t.cautionTitle(caution.type)} — ${t.cautionBody(caution)}',
              style: const TextStyle(fontSize: 12, height: 1.35),
            ),
          ),
          ?trailing,
        ],
      ),
    );
  }
}

/// The steps of report Figure 3.20, the current one highlighted.
class OrderProgress extends StatelessWidget {
  const OrderProgress(this.status, {super.key});

  final PurchaseOrderStatus status;

  static const _flow = [
    PurchaseOrderStatus.pendingApproval,
    PurchaseOrderStatus.approved,
    PurchaseOrderStatus.messageGenerated,
    PurchaseOrderStatus.sent,
    PurchaseOrderStatus.confirmed,
    PurchaseOrderStatus.closed,
  ];

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    final index = _flow.indexOf(status);
    final ended =
        status == PurchaseOrderStatus.rejected ||
        status == PurchaseOrderStatus.canceled;
    return Wrap(
      key: const Key('orderProgress'),
      spacing: 6,
      runSpacing: 6,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        for (var i = 0; i < _flow.length; i++) ...[
          _Step(
            label: t.statusShort(_flow[i]),
            done: !ended && index > i,
            current: index == i,
            muted: ended,
          ),
          if (i < _flow.length - 1)
            const Icon(
              Icons.chevron_right_rounded,
              size: 18,
              color: AppColors.textMuted,
            ),
        ],
        if (ended) ...[const SizedBox(width: 6), PurchaseStatusChip(status)],
      ],
    );
  }
}

class _Step extends StatelessWidget {
  const _Step({
    required this.label,
    required this.done,
    required this.current,
    required this.muted,
  });

  final String label;
  final bool done;
  final bool current;
  final bool muted;

  @override
  Widget build(BuildContext context) {
    final color = current
        ? AppColors.primary
        : done
        ? AppColors.success
        : AppColors.textMuted;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: current ? AppColors.primarySoft : AppColors.surface,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: color.withValues(alpha: muted ? 0.3 : 0.6),
          width: current ? 2 : 1,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            done
                ? Icons.check_circle_rounded
                : (current
                      ? Icons.radio_button_checked
                      : Icons.radio_button_unchecked),
            size: 14,
            color: color,
          ),
          const SizedBox(width: 4),
          Text(
            label,
            style: TextStyle(
              fontSize: 12,
              color: color,
              fontWeight: current ? FontWeight.w800 : FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}

/// A titled white card section.
class SectionCard extends StatelessWidget {
  const SectionCard({
    super.key,
    required this.title,
    required this.child,
    this.trailing,
    this.icon,
  });

  final String title;
  final Widget child;
  final Widget? trailing;
  final IconData? icon;

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(AppSpacing.lg + 2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              if (icon != null) ...[
                Icon(icon, size: 20, color: AppColors.primary),
                const SizedBox(width: 8),
              ],
              Expanded(
                child: Text(
                  title,
                  style: const TextStyle(
                    fontSize: 16.5,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
              ?trailing,
            ],
          ),
          const SizedBox(height: AppSpacing.md),
          child,
        ],
      ),
    ),
  );
}

/// Page frame with the usual ClixSell paddings and max width.
class PurchasePageFrame extends StatelessWidget {
  const PurchasePageFrame({
    super.key,
    required this.children,
    this.maxWidth = 1240,
  });

  final List<Widget> children;
  final double maxWidth;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, c) {
      final h = c.maxWidth < 600 ? AppSpacing.lg : AppSpacing.xxl;
      return SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(h, AppSpacing.xl, h, AppSpacing.xxl),
        child: Center(
          child: ConstrainedBox(
            constraints: BoxConstraints(maxWidth: maxWidth),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: children,
            ),
          ),
        ),
      );
    },
  );
}

/// The message text shown exactly as stored, in the direction of ITS language.
class MessageTextBox extends StatelessWidget {
  const MessageTextBox({super.key, required this.text, required this.language});

  final String text;
  final String language;

  @override
  Widget build(BuildContext context) => Container(
    key: const Key('messageText'),
    constraints: const BoxConstraints(maxHeight: 280),
    padding: const EdgeInsets.all(AppSpacing.md),
    decoration: BoxDecoration(
      color: AppColors.background,
      borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      border: Border.all(color: AppColors.border),
    ),
    // Plain Text in a SelectionArea over the full width, in the direction of the
    // MESSAGE's language (see WhatsappNumber for why not SelectableText).
    child: SingleChildScrollView(
      child: SizedBox(
        width: double.infinity,
        child: SelectionArea(
          child: Text(
            text,
            textDirection: language == 'ar'
                ? TextDirection.rtl
                : TextDirection.ltr,
            textAlign: TextAlign.start,
            style: const TextStyle(fontSize: 13.5, height: 1.6),
          ),
        ),
      ),
    ),
  );
}

/// The backend's length report for one language, as a bar.
class LengthMeter extends StatelessWidget {
  const LengthMeter(this.report, {super.key});

  final MessageLength report;

  @override
  Widget build(BuildContext context) {
    final t = PurchaseOrderStrings.of(context);
    final ok = report.alwaysFits;
    final value = report.limit == 0
        ? 0.0
        : (report.maxPossibleLength / report.limit).clamp(0.0, 1.0);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          t.lengthLine(
            report.language,
            report.length,
            report.maxPossibleLength,
            report.limit,
          ),
          style: const TextStyle(fontSize: 12.5),
        ),
        const SizedBox(height: 4),
        LinearProgressIndicator(
          value: value,
          minHeight: 7,
          borderRadius: BorderRadius.circular(6),
          color: ok ? AppColors.success : AppColors.danger,
          backgroundColor: AppColors.divider,
        ),
      ],
    );
  }
}

/// "2026-11-24T10:00:00" → "2026-11-24 10:00".
String shortTime(String? t) => t == null
    ? '—'
    : (t.length >= 16 ? t.substring(0, 16).replaceFirst('T', ' ') : t);
