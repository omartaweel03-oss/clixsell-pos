import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../l10n/product_file_strings.dart';
import '../../models/product.dart';
import '../../models/product_category.dart';
import '../../models/product_file.dart';
import '../../state/product_store.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/stat_card.dart';
import '../../widgets/stock_status_badge.dart';
import '../../services/product_file_client.dart';
import 'product_delete.dart';
import 'product_import_dialog.dart';
import 'widgets/product_table.dart';

/// Inventory Management page: header, live statistics, search/filters,
/// and the product list with edit / delete actions.
///
/// With [readOnly] (Cashiers) the add / edit / delete actions are hidden.
/// The backend refuses those operations for Cashiers in any case.
class InventoryPage extends StatefulWidget {
  const InventoryPage({
    super.key,
    required this.onAddProduct,
    required this.onEditProduct,
    this.onOpenProduct,
    this.readOnly = false,
    this.productFiles,
    this.fileIO = const PickerProductFileIO(),
  });

  final VoidCallback onAddProduct;
  final ValueChanged<Product> onEditProduct;

  /// Opens Product Details (Figure 3.4) – Store Admins only; null for Cashiers.
  final ValueChanged<Product>? onOpenProduct;
  final bool readOnly;

  /// Import / export of product files (UR-M11, Figure 3.3). Store Admins only:
  /// null (no buttons) for Cashiers. The backend refuses other roles anyway.
  final ProductFileApi? productFiles;
  final ProductFileIO fileIO;

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

  bool _exporting = false;
  bool _importing = false;

  void _snack(String text, {bool error = false}) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(text),
          backgroundColor: error ? AppColors.danger : null,
        ),
      );
  }

  /// Export: the server's CSV of this store, saved / downloaded as a file.
  Future<void> _export() async {
    final api = widget.productFiles;
    if (api == null || _exporting) return;
    final t = ProductFileStrings.of(context);
    setState(() => _exporting = true);
    try {
      final f = await api.export();
      final saved = await widget.fileIO.save(
        f.fileName,
        Uint8List.fromList(f.bytes),
      );
      if (!mounted) return;
      _snack(saved ? t.exported(f.count, f.fileName) : t.exportCancelled);
    } on ApiException {
      if (mounted) _snack(t.exportFailed, error: true);
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  /// Import: choose a CSV, the server checks it (nothing saved), then the
  /// preview dialog creates the products only after a confirmation.
  Future<void> _import() async {
    final api = widget.productFiles;
    if (api == null || _importing) return;
    final t = ProductFileStrings.of(context);
    final store = ProductStoreScope.read(context);
    setState(() => _importing = true);
    try {
      final picked = await widget.fileIO.pickCsv();
      if (picked == null || !mounted) return;
      final preview = await api.preview(picked.name, picked.bytes);
      if (!mounted) return;
      setState(() => _importing = false);
      final result = await showDialog<ImportResult>(
        context: context,
        barrierDismissible: false,
        builder: (_) => ProductImportDialog(
          api: api,
          fileName: picked.name,
          bytes: picked.bytes,
          preview: preview,
          isImported: (barcodes) async {
            await store.load();
            final have = {for (final p in store.products) p.barcode};
            return barcodes.isNotEmpty && barcodes.every(have.contains);
          },
        ),
      );
      if (result != null) {
        await store.load();
        if (mounted) _snack(t.created(result.created));
      }
    } on ApiException catch (e) {
      if (mounted) {
        _snack(
          e.fieldErrors['content'] ??
              (e.isNetworkError ? t.readFailed : e.message),
          error: true,
        );
      }
    } catch (_) {
      if (mounted) _snack(t.readFailed, error: true);
    } finally {
      if (mounted) setState(() => _importing = false);
    }
  }

  /// Same confirmation, DELETE and Undo as Product Details (shared helper).
  Future<void> _deleteProduct(Product product) =>
      deleteProductWithUndo(context, product);

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
        final horizontal = constraints.maxWidth < 600
            ? AppSpacing.lg
            : AppSpacing.xxl;
        return SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(
            horizontal,
            AppSpacing.xl,
            horizontal,
            AppSpacing.xxl,
          ),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1400),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _Header(
                    onAddProduct: widget.readOnly ? null : widget.onAddProduct,
                    onImport: widget.readOnly || widget.productFiles == null
                        ? null
                        : _import,
                    onExport: widget.readOnly || widget.productFiles == null
                        ? null
                        : _export,
                    importing: _importing,
                    exporting: _exporting,
                  ),
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
                              onAction: widget.readOnly
                                  ? null
                                  : widget.onAddProduct,
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
                              onEdit: widget.readOnly
                                  ? null
                                  : widget.onEditProduct,
                              onDelete: widget.readOnly ? null : _deleteProduct,
                              onOpen: widget.readOnly
                                  ? null
                                  : widget.onOpenProduct,
                            ),
                            const SizedBox(height: AppSpacing.lg),
                            _Pagination(
                              page: page,
                              pageCount: pageCount,
                              label: s.showingRange(
                                start + 1,
                                end,
                                filtered.length,
                              ),
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
  const _Header({
    required this.onAddProduct,
    this.onImport,
    this.onExport,
    this.importing = false,
    this.exporting = false,
  });

  /// Null when the user may not add products (Cashier).
  final VoidCallback? onAddProduct;

  /// Import / export (Figure 3.3); null when not available (Cashier).
  final VoidCallback? onImport;
  final VoidCallback? onExport;
  final bool importing;
  final bool exporting;

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
          onAddProduct == null
              ? s.inventoryDescriptionCashier
              : s.inventoryDescription,
          style: const TextStyle(
            fontSize: 14.5,
            color: AppColors.textSecondary,
            height: 1.4,
          ),
        ),
      ],
    );

    final add = FilledButton.icon(
      key: const Key('headerAddProductButton'),
      onPressed: onAddProduct,
      icon: const Icon(Icons.add_rounded, size: 20),
      label: Text(s.addProduct),
    );
    final f = ProductFileStrings.of(context);
    Widget spinner() => const SizedBox(
      width: 18,
      height: 18,
      child: CircularProgressIndicator(strokeWidth: 2),
    );
    // Figure 3.3: Import and Export next to "Add product".
    final button = Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        if (onImport != null)
          Tooltip(
            message: f.importTooltip,
            child: OutlinedButton.icon(
              key: const Key('headerImportButton'),
              onPressed: importing || exporting ? null : onImport,
              icon: importing
                  ? spinner()
                  : const Icon(Icons.upload_file_rounded, size: 20),
              label: Text(f.importButton),
            ),
          ),
        if (onExport != null)
          Tooltip(
            message: f.exportTooltip,
            child: OutlinedButton.icon(
              key: const Key('headerExportButton'),
              onPressed: importing || exporting ? null : onExport,
              icon: exporting
                  ? spinner()
                  : const Icon(Icons.download_rounded, size: 20),
              label: Text(f.exportButton),
            ),
          ),
        add,
      ],
    );

    if (onAddProduct == null) return titleBlock;

    return LayoutBuilder(
      builder: (context, c) {
        if (c.maxWidth < 640) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              titleBlock,
              const SizedBox(height: AppSpacing.lg),
              button,
            ],
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

    // A2: stable accessible name while typing (the hint disappears then).
    final search = MergeSemantics(
      child: Semantics(
        label: s.searchHint,
        child: TextField(
          key: const Key('searchField'),
          controller: searchController,
          onChanged: onSearch,
          decoration: InputDecoration(
            hintText: s.searchHint,
            prefixIcon: const Icon(
              Icons.search_rounded,
              color: AppColors.textMuted,
            ),
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
            child: Text(
              c.label(arabic: s.isArabic),
              overflow: TextOverflow.ellipsis,
            ),
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
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: controls,
        ),
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

  /// Null hides the button (e.g. a Cashier cannot add products).
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
              icon: Icon(actionIcon, size: 18),
              label: Text(actionLabel),
            ),
          ],
        ],
      ),
    );
  }
}
