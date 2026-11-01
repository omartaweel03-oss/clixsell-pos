import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/product.dart';
import '../../models/product_category.dart';
import '../../services/product_api_client.dart';
import '../../state/product_store.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/stat_card.dart';
import '../../widgets/stock_status_badge.dart';
import 'widgets/confirm_dialogs.dart';
import 'widgets/product_table.dart';

/// Inventory Management page: header, live statistics, search/filters,
/// and the product list with edit / delete actions.
class InventoryPage extends StatefulWidget {
  const InventoryPage({
    super.key,
    required this.onAddProduct,
    required this.onEditProduct,
  });

  final VoidCallback onAddProduct;
  final ValueChanged<Product> onEditProduct;

  @override
  State<InventoryPage> createState() => _InventoryPageState();
}

class _InventoryPageState extends State<InventoryPage> {
  static const int _pageSize = 8;

  final _searchController = TextEditingController();
  String _query = '';
  ProductCategory? _categoryFilter;
  StockStatus? _statusFilter;
  int _page = 0;

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  bool get _hasFilters =>
      _query.isNotEmpty || _categoryFilter != null || _statusFilter != null;

  List<Product> _applyFilters(List<Product> all, AppStrings s) {
    final q = _query.trim().toLowerCase();
    return all.where((p) {
      if (_categoryFilter != null && p.category != _categoryFilter) {
        return false;
      }
      if (_statusFilter != null && p.stockStatus != _statusFilter) {
        return false;
      }
      if (q.isEmpty) return true;
      return p.name.toLowerCase().contains(q) ||
          p.barcode.contains(q) ||
          p.supplier.toLowerCase().contains(q) ||
          p.category.labelEn.toLowerCase().contains(q) ||
          p.category.labelAr.contains(q);
    }).toList();
  }

  void _clearFilters() {
    setState(() {
      _searchController.clear();
      _query = '';
      _categoryFilter = null;
      _statusFilter = null;
      _page = 0;
    });
  }

  void _toggleStatusFilter(StockStatus? status) {
    setState(() {
      _statusFilter = _statusFilter == status ? null : status;
      _page = 0;
    });
  }

  Future<void> _deleteProduct(Product product) async {
    final s = AppStrings.of(context);
    final store = ProductStoreScope.read(context);
    final messenger = ScaffoldMessenger.of(context);

    final confirmed = await confirmDeleteProduct(context, product);
    if (!confirmed || !mounted) return;

    final index = store.products.indexWhere((p) => p.id == product.id);
    final Product? removed;
    try {
      removed = await store.deleteProduct(product.id); // DELETE /api/products/{id}
    } on ApiException catch (e) {
      _showError(messenger, s.deleteFailed(_describe(e, s)));
      return;
    }
    if (removed == null) return;
    final Product deleted = removed;

    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(s.productDeleted(deleted.name)),
          duration: const Duration(seconds: 5),
          persist: false, // auto-hide even though it has an Undo action
          action: SnackBarAction(
            label: s.undo,
            textColor: AppColors.primarySoft,
            onPressed: () async {
              try {
                await store.restoreProduct(deleted, index: index);
              } on ApiException catch (e) {
                _showError(messenger, s.restoreFailed(_describe(e, s)));
              }
            },
          ),
        ),
      );
  }

  static String _describe(ApiException e, AppStrings s) =>
      e.isNetworkError ? s.networkError : e.message;

  static void _showError(ScaffoldMessengerState messenger, String message) {
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(message),
        backgroundColor: AppColors.danger,
      ));
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final store = ProductStoreScope.of(context); // rebuilds on every change
    final stats = store.stats;
    final filtered = _applyFilters(store.products, s);

    final pageCount = (filtered.length / _pageSize).ceil();
    final page = pageCount == 0 ? 0 : _page.clamp(0, pageCount - 1);
    final start = page * _pageSize;
    final end = (start + _pageSize).clamp(0, filtered.length);
    final visible = filtered.sublist(start, end);

    return LayoutBuilder(
      builder: (context, constraints) {
        final horizontal = constraints.maxWidth < 600 ? AppSpacing.lg : AppSpacing.xxl;
        return SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(
              horizontal, AppSpacing.xl, horizontal, AppSpacing.xxl),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1400),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _Header(onAddProduct: widget.onAddProduct),
                  const SizedBox(height: AppSpacing.xl),
                  if (store.hasLoaded) ...[
                    _StatsGrid(
                      stats: stats,
                      statusFilter: _statusFilter,
                      onFilter: _toggleStatusFilter,
                    ),
                    const SizedBox(height: AppSpacing.xl),
                  ],
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(AppSpacing.lg + 4),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          _Toolbar(
                            searchController: _searchController,
                            category: _categoryFilter,
                            status: _statusFilter,
                            hasFilters: _hasFilters,
                            onSearch: (v) => setState(() {
                              _query = v;
                              _page = 0;
                            }),
                            onCategory: (c) => setState(() {
                              _categoryFilter = c;
                              _page = 0;
                            }),
                            onStatus: (st) => setState(() {
                              _statusFilter = st;
                              _page = 0;
                            }),
                            onClear: _clearFilters,
                          ),
                          const SizedBox(height: AppSpacing.lg),
                          if (!store.hasLoaded && store.isLoading)
                            _LoadingState(message: s.loadingProducts)
                          else if (!store.hasLoaded)
                            _EmptyState(
                              key: const Key('serverErrorState'),
                              icon: Icons.cloud_off_rounded,
                              title: store.loadError?.isNetworkError ?? true
                                  ? s.serverUnreachableTitle
                                  : s.loadFailedTitle,
                              body: store.loadError?.isNetworkError ?? true
                                  ? s.serverUnreachableBody(store.serverUrl)
                                  : store.loadError!.message,
                              actionLabel: s.retry,
                              actionIcon: Icons.refresh_rounded,
                              onAction: store.load,
                            )
                          else if (store.products.isEmpty)
                            _EmptyState(
                              icon: Icons.inventory_2_outlined,
                              title: s.emptyTitle,
                              body: s.emptyBody,
                              actionLabel: s.addProduct,
                              actionIcon: Icons.add_rounded,
                              onAction: widget.onAddProduct,
                            )
                          else if (filtered.isEmpty)
                            _EmptyState(
                              icon: Icons.search_off_rounded,
                              title: s.noResultsTitle,
                              body: s.noResultsBody,
                              actionLabel: s.clearFilters,
                              actionIcon: Icons.filter_alt_off_outlined,
                              onAction: _clearFilters,
                            )
                          else ...[
                            ProductTable(
                              products: visible,
                              onEdit: widget.onEditProduct,
                              onDelete: _deleteProduct,
                            ),
                            const SizedBox(height: AppSpacing.lg),
                            _Pagination(
                              page: page,
                              pageCount: pageCount,
                              label: s.showingRange(
                                  start + 1, end, filtered.length),
                              onPage: (p) => setState(() => _page = p),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

class _Header extends StatelessWidget {
  const _Header({required this.onAddProduct});

  final VoidCallback onAddProduct;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final titleBlock = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          s.inventoryTitle,
          style: const TextStyle(
            fontSize: 28,
            fontWeight: FontWeight.w800,
            color: AppColors.textPrimary,
          ),
        ),
        const SizedBox(height: AppSpacing.xs + 2),
        Text(
          s.inventoryDescription,
          style: const TextStyle(
            fontSize: 14.5,
            color: AppColors.textSecondary,
            height: 1.4,
          ),
        ),
      ],
    );

    final button = FilledButton.icon(
      key: const Key('headerAddProductButton'),
      onPressed: onAddProduct,
      icon: const Icon(Icons.add_rounded, size: 20),
      label: Text(s.addProduct),
    );

    return LayoutBuilder(
      builder: (context, c) {
        if (c.maxWidth < 640) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [titleBlock, const SizedBox(height: AppSpacing.lg), button],
          );
        }
        return Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Expanded(child: titleBlock),
            const SizedBox(width: AppSpacing.lg),
            button,
          ],
        );
      },
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Statistics
// ─────────────────────────────────────────────────────────────────────────────

class _StatsGrid extends StatelessWidget {
  const _StatsGrid({
    required this.stats,
    required this.statusFilter,
    required this.onFilter,
  });

  final InventoryStats stats;
  final StockStatus? statusFilter;
  final ValueChanged<StockStatus?> onFilter;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final cards = <Widget>[
      StatCard(
        key: const Key('statTotal'),
        title: s.statTotalProducts,
        value: Formatters.integer(stats.totalProducts),
        subtitle: s.statTotalHint,
        icon: Icons.shopping_bag_outlined,
        color: AppColors.primary,
        softColor: AppColors.primarySoft,
        selected: false,
        onTap: statusFilter == null ? null : () => onFilter(null),
        tooltip: statusFilter == null ? null : s.clearFilters,
      ),
      StatCard(
        key: const Key('statLow'),
        title: s.statLowStock,
        value: Formatters.integer(stats.lowStock),
        subtitle: s.statLowHint,
        icon: Icons.warning_amber_rounded,
        color: AppColors.warning,
        softColor: AppColors.warningSoft,
        selected: statusFilter == StockStatus.lowStock,
        onTap: () => onFilter(StockStatus.lowStock),
      ),
      StatCard(
        key: const Key('statOut'),
        title: s.statOutOfStock,
        value: Formatters.integer(stats.outOfStock),
        subtitle: s.statOutHint,
        icon: Icons.remove_shopping_cart_outlined,
        color: AppColors.danger,
        softColor: AppColors.dangerSoft,
        selected: statusFilter == StockStatus.outOfStock,
        onTap: () => onFilter(StockStatus.outOfStock),
      ),
      StatCard(
        key: const Key('statValue'),
        title: s.statInventoryValue,
        value: Formatters.compactCurrency(stats.inventoryValue),
        subtitle: s.statValueHint,
        icon: Icons.account_balance_wallet_outlined,
        color: AppColors.success,
        softColor: AppColors.successSoft,
      ),
    ];

    return LayoutBuilder(
      builder: (context, c) {
        final columns = c.maxWidth >= 1000 ? 4 : (c.maxWidth >= 520 ? 2 : 1);
        const gap = AppSpacing.lg;
        final width = (c.maxWidth - gap * (columns - 1)) / columns;
        return Wrap(
          spacing: gap,
          runSpacing: gap,
          children: [
            for (final card in cards) SizedBox(width: width, child: card),
          ],
        );
      },
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Toolbar (search + filters)
// ─────────────────────────────────────────────────────────────────────────────

class _Toolbar extends StatelessWidget {
  const _Toolbar({
    required this.searchController,
    required this.category,
    required this.status,
    required this.hasFilters,
    required this.onSearch,
    required this.onCategory,
    required this.onStatus,
    required this.onClear,
  });

  final TextEditingController searchController;
  final ProductCategory? category;
  final StockStatus? status;
  final bool hasFilters;
  final ValueChanged<String> onSearch;
  final ValueChanged<ProductCategory?> onCategory;
  final ValueChanged<StockStatus?> onStatus;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);

    final search = TextField(
      key: const Key('searchField'),
      controller: searchController,
      onChanged: onSearch,
      decoration: InputDecoration(
        hintText: s.searchHint,
        prefixIcon: const Icon(Icons.search_rounded, color: AppColors.textMuted),
        fillColor: AppColors.background,
        suffixIcon: ValueListenableBuilder<TextEditingValue>(
          valueListenable: searchController,
          builder: (context, value, _) => value.text.isEmpty
              ? const SizedBox.shrink()
              : IconButton(
                  icon: const Icon(Icons.close_rounded, size: 18),
                  onPressed: () {
                    searchController.clear();
                    onSearch('');
                  },
                ),
        ),
      ),
    );

    final categoryDropdown = DropdownButtonFormField<ProductCategory?>(
      key: ValueKey('categoryFilter-$category'),
      initialValue: category,
      isExpanded: true,
      decoration: const InputDecoration(
        prefixIcon: Icon(Icons.category_outlined, size: 20),
        fillColor: AppColors.background,
      ),
      items: [
        DropdownMenuItem(value: null, child: Text(s.allCategories)),
        for (final c in ProductCategory.values)
          DropdownMenuItem(
            value: c,
            child: Text(c.label(arabic: s.isArabic),
                overflow: TextOverflow.ellipsis),
          ),
      ],
      onChanged: onCategory,
    );

    final statusDropdown = DropdownButtonFormField<StockStatus?>(
      key: ValueKey('statusFilter-$status'),
      initialValue: status,
      isExpanded: true,
      decoration: const InputDecoration(
        prefixIcon: Icon(Icons.filter_list_rounded, size: 20),
        fillColor: AppColors.background,
      ),
      items: [
        DropdownMenuItem(value: null, child: Text(s.allStatuses)),
        for (final st in StockStatus.values)
          DropdownMenuItem(
            value: st,
            child: Text(StockStatusStyle.label(st, s)),
          ),
      ],
      onChanged: onStatus,
    );

    final clear = hasFilters
        ? TextButton.icon(
            onPressed: onClear,
            icon: const Icon(Icons.filter_alt_off_outlined, size: 18),
            label: Text(s.clearFilters),
          )
        : null;

    return LayoutBuilder(
      builder: (context, c) {
        if (c.maxWidth >= 860) {
          return Row(
            children: [
              Expanded(flex: 5, child: search),
              const SizedBox(width: AppSpacing.md),
              Expanded(flex: 2, child: categoryDropdown),
              const SizedBox(width: AppSpacing.md),
              Expanded(flex: 2, child: statusDropdown),
              if (clear != null) ...[
                const SizedBox(width: AppSpacing.sm),
                clear,
              ],
            ],
          );
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            search,
            const SizedBox(height: AppSpacing.md),
            Row(
              children: [
                Expanded(child: categoryDropdown),
                const SizedBox(width: AppSpacing.md),
                Expanded(child: statusDropdown),
              ],
            ),
            if (clear != null)
              Align(alignment: AlignmentDirectional.centerEnd, child: clear),
          ],
        );
      },
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pagination & empty state
// ─────────────────────────────────────────────────────────────────────────────

class _Pagination extends StatelessWidget {
  const _Pagination({
    required this.page,
    required this.pageCount,
    required this.label,
    required this.onPage,
  });

  final int page;
  final int pageCount;
  final String label;
  final ValueChanged<int> onPage;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final info = Text(
      label,
      style: const TextStyle(color: AppColors.textSecondary, fontSize: 13),
    );
    if (pageCount <= 1) return info;

    Widget pageButton(int i) {
      final selected = i == page;
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 3),
        child: Material(
          color: selected ? AppColors.primary : AppColors.background,
          shape: const CircleBorder(),
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: selected ? null : () => onPage(i),
            child: SizedBox(
              width: 34,
              height: 34,
              child: Center(
                child: Text(
                  '${i + 1}',
                  style: TextStyle(
                    color: selected ? Colors.white : AppColors.textPrimary,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ),
          ),
        ),
      );
    }

    // In RTL, Flutter mirrors the chevron icons automatically.
    final controls = Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          tooltip: s.previousPage,
          onPressed: page > 0 ? () => onPage(page - 1) : null,
          icon: const Icon(Icons.chevron_left_rounded),
        ),
        for (var i = 0; i < pageCount; i++) pageButton(i),
        IconButton(
          tooltip: s.nextPage,
          onPressed: page < pageCount - 1 ? () => onPage(page + 1) : null,
          icon: const Icon(Icons.chevron_right_rounded),
        ),
      ],
    );

    return Wrap(
      alignment: WrapAlignment.spaceBetween,
      crossAxisAlignment: WrapCrossAlignment.center,
      runSpacing: AppSpacing.sm,
      spacing: AppSpacing.lg,
      children: [
        info,
        SingleChildScrollView(scrollDirection: Axis.horizontal, child: controls),
      ],
    );
  }
}

class _LoadingState extends StatelessWidget {
  const _LoadingState({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 56),
      child: Column(
        children: [
          const CircularProgressIndicator(),
          const SizedBox(height: AppSpacing.lg),
          Text(message, style: const TextStyle(color: AppColors.textSecondary)),
        ],
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({
    super.key,
    required this.icon,
    required this.title,
    required this.body,
    required this.actionLabel,
    required this.actionIcon,
    required this.onAction,
  });

  final IconData icon;
  final String title;
  final String body;
  final String actionLabel;
  final IconData actionIcon;
  final VoidCallback onAction;

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
          const SizedBox(height: AppSpacing.lg),
          FilledButton.icon(
            onPressed: onAction,
            icon: Icon(actionIcon, size: 18),
            label: Text(actionLabel),
          ),
        ],
      ),
    );
  }
}
