import 'package:flutter/material.dart';

import '../l10n/app_strings.dart';
import '../models/product.dart';
import '../theme/app_theme.dart';

/// Visual style (colour, icon, label) for each [StockStatus].
class StockStatusStyle {
  const StockStatusStyle(this.color, this.softColor, this.icon);

  final Color color;
  final Color softColor;
  final IconData icon;

  static StockStatusStyle of(StockStatus status) {
    switch (status) {
      case StockStatus.inStock:
        return const StockStatusStyle(
          AppColors.success,
          AppColors.successSoft,
          Icons.check_circle_rounded,
        );
      case StockStatus.lowStock:
        return const StockStatusStyle(
          AppColors.warning,
          AppColors.warningSoft,
          Icons.error_rounded,
        );
      case StockStatus.outOfStock:
        return const StockStatusStyle(
          AppColors.danger,
          AppColors.dangerSoft,
          Icons.cancel_rounded,
        );
    }
  }

  static String label(StockStatus status, AppStrings s) {
    switch (status) {
      case StockStatus.inStock:
        return s.statusInStock;
      case StockStatus.lowStock:
        return s.statusLowStock;
      case StockStatus.outOfStock:
        return s.statusOutOfStock;
    }
  }
}

/// Rounded pill showing a product's stock status (In Stock / Low / Out).
class StockStatusBadge extends StatelessWidget {
  const StockStatusBadge({super.key, required this.status});

  final StockStatus status;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final style = StockStatusStyle.of(status);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: style.softColor,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: style.color.withValues(alpha: 0.35)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(style.icon, size: 14, color: style.color),
          const SizedBox(width: 5),
          Flexible(
            child: Text(
              StockStatusStyle.label(status, s),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: style.color,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
