import 'package:flutter/material.dart';

import '../../l10n/shift_strings.dart';
import '../../models/cash_shift.dart';
import '../../services/shift_api_client.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import 'shift_dialogs.dart';

/// The user's own cash shift on the POS screen: none / open on a drawer, with Open and
/// Close. When the store requires shifts and none is open, it is shown as a warning.
class PosShiftBar extends StatefulWidget {
  const PosShiftBar({super.key, required this.api, this.onMessage});

  final ShiftApiClient api;
  final void Function(String message, {bool error})? onMessage;

  @override
  State<PosShiftBar> createState() => PosShiftBarState();
}

class PosShiftBarState extends State<PosShiftBar> {
  CurrentShift? _current;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    reload();
  }

  Future<void> reload() async {
    try {
      final c = await widget.api.current();
      if (mounted) setState(() => _current = c);
    } catch (_) {
      // the bar stays as it was; sales still go to the backend, which decides
    }
  }

  Future<void> _open() async {
    final t = ShiftStrings.of(context);
    setState(() => _busy = true);
    try {
      final c = await widget.api.current();
      if (!mounted) return;
      final opened = await OpenShiftDialog.show(context, widget.api, c.drawers);
      if (opened != null) widget.onMessage?.call(t.shiftOpened(opened.drawerName));
    } catch (e) {
      if (mounted) widget.onMessage?.call(shiftError(context, e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
      await reload();
    }
  }

  Future<void> _close() async {
    final t = ShiftStrings.of(context);
    setState(() => _busy = true);
    try {
      final c = await widget.api.current(); // fresh amounts from the backend
      if (!mounted) return;
      final shift = c.shift;
      if (shift == null) return;
      final closed = await CloseShiftDialog.show(context, widget.api, shift);
      if (closed != null) {
        widget.onMessage?.call(t.shiftClosed(signedMoney(closed.difference ?? 0)));
      }
    } catch (e) {
      if (mounted) widget.onMessage?.call(shiftError(context, e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
      await reload();
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = ShiftStrings.of(context);
    final c = _current;
    if (c == null) return const SizedBox.shrink();
    final shift = c.shift;
    final warn = shift == null && c.requireCashShift;
    final color = shift != null ? AppColors.success : (warn ? AppColors.danger : AppColors.textSecondary);
    final soft = shift != null ? AppColors.successSoft : (warn ? AppColors.dangerSoft : AppColors.surface);
    return Container(
      key: const Key('posShiftBar'),
      margin: const EdgeInsets.only(bottom: AppSpacing.md),
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
      decoration: BoxDecoration(
        color: soft,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: [
          Icon(shift != null ? Icons.lock_open_rounded : Icons.point_of_sale_outlined, color: color, size: 20),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Text(
              shift != null
                  ? '${t.shiftOpenOn(shift.drawerName)} · ${t.opening}: ${Formatters.currency(shift.openingCash)}'
                  : (warn ? t.shiftRequiredBanner : t.noShift),
              key: const Key('posShiftStatus'),
              style: TextStyle(color: color, fontWeight: FontWeight.w600),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (shift == null)
            FilledButton.icon(
              key: const Key('posOpenShift'),
              onPressed: _busy ? null : _open,
              icon: const Icon(Icons.play_arrow_rounded, size: 18),
              label: Text(t.openShift),
            )
          else
            OutlinedButton.icon(
              key: const Key('posCloseShift'),
              onPressed: _busy ? null : _close,
              icon: const Icon(Icons.lock_outline_rounded, size: 18),
              label: Text(t.closeShift),
            ),
        ],
      ),
    );
  }
}
