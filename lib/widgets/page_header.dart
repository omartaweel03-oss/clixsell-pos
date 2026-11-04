import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Title + description + optional action button, stacked on narrow screens.
/// Used by the Stores, Store Admins and Cashiers pages.
class PageHeader extends StatelessWidget {
  const PageHeader({
    super.key,
    required this.title,
    required this.description,
    this.action,
    this.onBack,
    this.backTooltip,
  });

  final String title;
  final String description;
  final Widget? action;
  final VoidCallback? onBack;
  final String? backTooltip;

  @override
  Widget build(BuildContext context) {
    final titleBlock = Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (onBack != null) ...[
          IconButton.outlined(
            key: const Key('pageBackButton'),
            tooltip: backTooltip,
            onPressed: onBack,
            style: IconButton.styleFrom(
              backgroundColor: AppColors.surface,
              side: const BorderSide(color: AppColors.border),
            ),
            icon: const BackButtonIcon(),
          ),
          const SizedBox(width: AppSpacing.lg),
        ],
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontSize: 28,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: AppSpacing.xs + 2),
              Text(
                description,
                style: const TextStyle(
                  fontSize: 14.5,
                  color: AppColors.textSecondary,
                  height: 1.4,
                ),
              ),
            ],
          ),
        ),
      ],
    );

    return LayoutBuilder(
      builder: (context, c) {
        if (action == null) return titleBlock;
        if (c.maxWidth < 640) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              titleBlock,
              const SizedBox(height: AppSpacing.lg),
              action!,
            ],
          );
        }
        return Row(
          children: [
            Expanded(child: titleBlock),
            const SizedBox(width: AppSpacing.lg),
            action!,
          ],
        );
      },
    );
  }
}

/// "Active" / "Inactive" pill for stores and accounts.
class ActiveStatusPill extends StatelessWidget {
  const ActiveStatusPill({
    super.key,
    required this.active,
    required this.activeLabel,
    required this.inactiveLabel,
  });

  final bool active;
  final String activeLabel;
  final String inactiveLabel;

  @override
  Widget build(BuildContext context) {
    final color = active ? AppColors.success : AppColors.textMuted;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: active ? AppColors.successSoft : AppColors.background,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.4)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            active
                ? Icons.check_circle_rounded
                : Icons.pause_circle_filled_rounded,
            size: 14,
            color: color,
          ),
          const SizedBox(width: 5),
          Text(
            active ? activeLabel : inactiveLabel,
            style: TextStyle(
              color: color,
              fontSize: 12,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

/// Centered icon + title + text + optional button, for empty or failed lists.
class MessageState extends StatelessWidget {
  const MessageState({
    super.key,
    required this.icon,
    required this.title,
    required this.body,
    this.actionLabel,
    this.actionIcon,
    this.onAction,
  });

  final IconData icon;
  final String title;
  final String body;
  final String? actionLabel;
  final IconData? actionIcon;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 48, horizontal: 16),
      child: Column(
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: const BoxDecoration(
              color: AppColors.primarySoft,
              shape: BoxShape.circle,
            ),
            child: Icon(icon, color: AppColors.primary, size: 34),
          ),
          const SizedBox(height: AppSpacing.lg),
          Text(
            title,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: AppSpacing.xs + 2),
          Text(
            body,
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppColors.textSecondary),
          ),
          if (onAction != null) ...[
            const SizedBox(height: AppSpacing.lg),
            FilledButton.icon(
              onPressed: onAction,
              icon: Icon(actionIcon ?? Icons.add_rounded, size: 18),
              label: Text(actionLabel ?? ''),
            ),
          ],
        ],
      ),
    );
  }
}

/// Asks the user to confirm an action. Returns true only on confirm.
Future<bool> confirmAction(
  BuildContext context, {
  required String title,
  required String body,
  required String confirmLabel,
  required String cancelLabel,
  IconData icon = Icons.warning_amber_rounded,
  Color color = AppColors.warning,
}) async {
  final result = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      icon: Icon(icon, color: color, size: 36),
      title: Text(title, textAlign: TextAlign.center),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 400),
        child: Text(
          body,
          textAlign: TextAlign.center,
          style: const TextStyle(color: AppColors.textSecondary, height: 1.5),
        ),
      ),
      actionsAlignment: MainAxisAlignment.center,
      actions: [
        OutlinedButton(
          onPressed: () => Navigator.of(ctx).pop(false),
          child: Text(cancelLabel),
        ),
        FilledButton(
          key: const Key('confirmActionButton'),
          style: FilledButton.styleFrom(backgroundColor: color),
          onPressed: () => Navigator.of(ctx).pop(true),
          child: Text(confirmLabel),
        ),
      ],
    ),
  );
  return result ?? false;
}
