import 'package:flutter/material.dart';

import '../l10n/app_strings.dart';
import '../theme/app_theme.dart';

/// Identifies the destinations that exist in this phase of the project.
enum SidebarDestination { products, addProduct, editProduct }

/// Dark navy navigation sidebar.
///
/// Only modules that are implemented are listed, so there are no dead buttons:
/// Products (inventory list), Add Product, and Edit Product (shown while a
/// product is being edited). The language button switches English/Arabic.
class AppSidebar extends StatelessWidget {
  const AppSidebar({
    super.key,
    required this.selected,
    required this.onSelect,
    required this.onToggleLanguage,
    this.compact = false,
    this.editingProductName,
  });

  final SidebarDestination selected;
  final ValueChanged<SidebarDestination> onSelect;
  final VoidCallback onToggleLanguage;

  /// Icon-only rail for medium-width screens.
  final bool compact;

  /// When not null an "Edit Product" entry is shown for the product being edited.
  final String? editingProductName;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);

    return Container(
      width: compact ? 84 : 248,
      color: AppColors.sidebar,
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _Brand(compact: compact),
            const SizedBox(height: AppSpacing.lg),
            if (!compact)
              Padding(
                padding: const EdgeInsetsDirectional.fromSTEB(24, 8, 24, 8),
                child: Text(
                  s.navSectionInventory,
                  style: const TextStyle(
                    color: AppColors.sidebarMuted,
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1.1,
                  ),
                ),
              ),
            _NavItem(
              icon: Icons.inventory_2_outlined,
              selectedIcon: Icons.inventory_2_rounded,
              label: s.navInventory,
              compact: compact,
              selected: selected == SidebarDestination.products,
              onTap: () => onSelect(SidebarDestination.products),
            ),
            _NavItem(
              icon: Icons.add_box_outlined,
              selectedIcon: Icons.add_box_rounded,
              label: s.navAddProduct,
              compact: compact,
              selected: selected == SidebarDestination.addProduct,
              onTap: () => onSelect(SidebarDestination.addProduct),
            ),
            if (editingProductName != null)
              _NavItem(
                icon: Icons.edit_outlined,
                selectedIcon: Icons.edit_rounded,
                label: s.navEditProduct,
                caption: editingProductName,
                compact: compact,
                selected: selected == SidebarDestination.editProduct,
                onTap: () => onSelect(SidebarDestination.editProduct),
              ),
            const Spacer(),
            Padding(
              padding: EdgeInsets.all(compact ? AppSpacing.md : AppSpacing.lg),
              child: Tooltip(
                message: s.languageToggleTooltip,
                child: OutlinedButton(
                  onPressed: onToggleLanguage,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: Colors.white,
                    side: BorderSide(
                      color: Colors.white.withValues(alpha: 0.25),
                    ),
                    padding: EdgeInsets.symmetric(
                      horizontal: compact ? 0 : 16,
                      vertical: 14,
                    ),
                  ),
                  child: compact
                      ? const Icon(Icons.translate_rounded, size: 20)
                      : Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            const Icon(Icons.translate_rounded, size: 18),
                            const SizedBox(width: AppSpacing.sm),
                            Text(s.languageToggle),
                          ],
                        ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Brand extends StatelessWidget {
  const _Brand({required this.compact});

  final bool compact;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final logo = Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        color: AppColors.primary,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      ),
      child: const Icon(Icons.point_of_sale_rounded, color: Colors.white),
    );

    if (compact) {
      return Padding(
        padding: const EdgeInsets.only(top: AppSpacing.xl),
        child: Center(child: logo),
      );
    }

    return Padding(
      padding: const EdgeInsetsDirectional.fromSTEB(24, 28, 24, 0),
      child: Row(
        children: [
          logo,
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  s.appName,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 22,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                Text(
                  s.appSubtitle,
                  style: const TextStyle(
                    color: AppColors.sidebarMuted,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _NavItem extends StatelessWidget {
  const _NavItem({
    required this.icon,
    required this.selectedIcon,
    required this.label,
    required this.selected,
    required this.onTap,
    required this.compact,
    this.caption,
  });

  final IconData icon;
  final IconData selectedIcon;
  final String label;
  final String? caption;
  final bool selected;
  final bool compact;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final fg = selected ? Colors.white : AppColors.sidebarText;
    final content = AnimatedContainer(
      duration: const Duration(milliseconds: 160),
      padding: EdgeInsets.symmetric(
        horizontal: compact ? 0 : 14,
        vertical: 12,
      ),
      decoration: BoxDecoration(
        color: selected ? AppColors.primary : Colors.transparent,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        boxShadow: selected
            ? [
                BoxShadow(
                  color: AppColors.primary.withValues(alpha: 0.35),
                  blurRadius: 12,
                  offset: const Offset(0, 4),
                ),
              ]
            : null,
      ),
      child: compact
          ? Icon(selected ? selectedIcon : icon, color: fg, size: 22)
          : Row(
              children: [
                Icon(selected ? selectedIcon : icon, color: fg, size: 21),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: fg,
                          fontSize: 14.5,
                          fontWeight:
                              selected ? FontWeight.w700 : FontWeight.w500,
                        ),
                      ),
                      if (caption != null)
                        Text(
                          caption!,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: fg.withValues(alpha: 0.75),
                            fontSize: 11.5,
                          ),
                        ),
                    ],
                  ),
                ),
              ],
            ),
    );

    final item = Padding(
      padding: EdgeInsets.symmetric(
        horizontal: compact ? 14 : 16,
        vertical: 3,
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
          hoverColor: Colors.white.withValues(alpha: 0.06),
          onTap: onTap,
          child: content,
        ),
      ),
    );

    return compact
        ? Tooltip(message: caption == null ? label : '$label · $caption', child: item)
        : item;
  }
}
