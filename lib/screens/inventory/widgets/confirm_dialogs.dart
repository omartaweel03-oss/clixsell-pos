import 'package:flutter/material.dart';

import '../../../l10n/app_strings.dart';
import '../../../models/product.dart';
import '../../../theme/app_theme.dart';
import '../../../widgets/product_thumbnail.dart';

/// Asks the user to confirm deleting [product]. Returns true only when the
/// user explicitly presses "Delete".
Future<bool> confirmDeleteProduct(BuildContext context, Product product) async {
  final s = AppStrings.of(context);
  final result = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      icon: Container(
        width: 56,
        height: 56,
        decoration: const BoxDecoration(
          color: AppColors.dangerSoft,
          shape: BoxShape.circle,
        ),
        child: const Icon(
          Icons.delete_forever_rounded,
          color: AppColors.danger,
          size: 30,
        ),
      ),
      title: Text(s.deleteTitle, textAlign: TextAlign.center),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.all(AppSpacing.md),
              decoration: BoxDecoration(
                color: AppColors.background,
                borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
              ),
              child: Row(
                children: [
                  ProductThumbnail(imageBytes: product.imageBytes, size: 40),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          product.name,
                          style: const TextStyle(fontWeight: FontWeight.w700),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        Text(
                          product.barcode,
                          style: const TextStyle(
                            color: AppColors.textSecondary,
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
            Text(
              s.deleteBody(product.name),
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.textSecondary, height: 1.5),
            ),
          ],
        ),
      ),
      actionsAlignment: MainAxisAlignment.center,
      actions: [
        OutlinedButton(
          onPressed: () => Navigator.of(ctx).pop(false),
          child: Text(s.cancel),
        ),
        FilledButton.icon(
          key: const Key('confirmDeleteButton'),
          style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
          onPressed: () => Navigator.of(ctx).pop(true),
          icon: const Icon(Icons.delete_outline_rounded, size: 18),
          label: Text(s.deleteConfirm),
        ),
      ],
    ),
  );
  return result ?? false;
}

/// Asks whether unsaved form changes may be discarded.
Future<bool> confirmDiscardChanges(BuildContext context) async {
  final s = AppStrings.of(context);
  final result = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      icon: const Icon(Icons.warning_amber_rounded,
          color: AppColors.warning, size: 36),
      title: Text(s.discardTitle, textAlign: TextAlign.center),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 380),
        child: Text(
          s.discardBody,
          textAlign: TextAlign.center,
          style: const TextStyle(color: AppColors.textSecondary, height: 1.5),
        ),
      ),
      actionsAlignment: MainAxisAlignment.center,
      actions: [
        OutlinedButton(
          onPressed: () => Navigator.of(ctx).pop(false),
          child: Text(s.keepEditing),
        ),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: AppColors.warning),
          onPressed: () => Navigator.of(ctx).pop(true),
          child: Text(s.discard),
        ),
      ],
    ),
  );
  return result ?? false;
}
