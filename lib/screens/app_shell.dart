import 'package:flutter/material.dart';

import '../l10n/app_strings.dart';
import '../models/product.dart';
import '../state/product_store.dart';
import '../theme/app_theme.dart';
import '../widgets/app_sidebar.dart';
import 'inventory/inventory_page.dart';
import 'inventory/product_form_page.dart';

/// Main layout: navy sidebar + content area.
///
/// Handles navigation between the Products list, Add Product and
/// Edit Product, and adapts to the screen size:
/// * ≥ 1100 px: full sidebar
/// * ≥ 720 px: compact icon sidebar
/// * < 720 px: top app bar with a drawer
class AppShell extends StatefulWidget {
  const AppShell({super.key, required this.onToggleLanguage});

  final VoidCallback onToggleLanguage;

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  final _scaffoldKey = GlobalKey<ScaffoldState>();

  SidebarDestination _destination = SidebarDestination.products;
  String? _editingProductId;
  GlobalKey<ProductFormPageState> _formKey = GlobalKey<ProductFormPageState>();

  bool get _onForm => _destination != SidebarDestination.products;

  /// Central navigation method used by the sidebar and page buttons.
  Future<void> _navigate(
    SidebarDestination destination, {
    Product? product,
  }) async {
    // Close the drawer on small screens.
    if (_scaffoldKey.currentState?.isDrawerOpen ?? false) {
      Navigator.of(context).pop();
    }

    final sameEdit = destination == SidebarDestination.editProduct &&
        _destination == SidebarDestination.editProduct &&
        (product == null || product.id == _editingProductId);
    if (destination == _destination &&
        (destination != SidebarDestination.editProduct || sameEdit)) {
      return; // already there
    }

    // Protect unsaved form data.
    if (_onForm) {
      final canLeave = await _formKey.currentState?.confirmLeave() ?? true;
      if (!canLeave || !mounted) return;
    }

    setState(() {
      _destination = destination;
      if (destination == SidebarDestination.editProduct) {
        _editingProductId = product?.id ?? _editingProductId;
      } else {
        _editingProductId = null;
      }
      _formKey = GlobalKey<ProductFormPageState>();
    });
  }

  void _onSaved(Product product, {required bool wasEditing}) {
    final s = AppStrings.of(context);
    setState(() {
      _destination = SidebarDestination.products;
      _editingProductId = null;
    });
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Row(
            children: [
              const Icon(Icons.check_circle_rounded,
                  color: AppColors.success, size: 20),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(wasEditing
                    ? s.productUpdated(product.name)
                    : s.productAdded(product.name)),
              ),
            ],
          ),
        ),
      );
  }

  void _backToList() {
    setState(() {
      _destination = SidebarDestination.products;
      _editingProductId = null;
    });
  }

  Widget _buildContent(ProductStore store) {
    switch (_destination) {
      case SidebarDestination.products:
        return InventoryPage(
          key: const ValueKey('inventory'),
          onAddProduct: () => _navigate(SidebarDestination.addProduct),
          onEditProduct: (p) =>
              _navigate(SidebarDestination.editProduct, product: p),
        );
      case SidebarDestination.addProduct:
        return ProductFormPage(
          key: _formKey,
          onSaved: (p) => _onSaved(p, wasEditing: false),
          onCancel: _backToList,
        );
      case SidebarDestination.editProduct:
        final product =
            _editingProductId == null ? null : store.findById(_editingProductId!);
        if (product == null) {
          return _MissingProduct(onBack: _backToList);
        }
        return ProductFormPage(
          key: _formKey,
          initialProduct: product,
          onSaved: (p) => _onSaved(p, wasEditing: true),
          onCancel: _backToList,
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final store = ProductStoreScope.of(context);
    final editingName = _editingProductId == null
        ? null
        : store.findById(_editingProductId!)?.name;

    final content = AnimatedSwitcher(
      duration: const Duration(milliseconds: 220),
      switchInCurve: Curves.easeOut,
      // Expand children to the full content area so short pages (the form)
      // stay top-aligned instead of being centred by the default Stack.
      layoutBuilder: (current, previous) => Stack(
        fit: StackFit.expand,
        children: [...previous, ?current],
      ),
      child: KeyedSubtree(
        key: ValueKey('$_destination-$_editingProductId'),
        child: _buildContent(store),
      ),
    );

    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;

        Widget sidebar({required bool compact}) => AppSidebar(
              selected: _destination,
              compact: compact,
              editingProductName: editingName,
              onSelect: (d) => _navigate(d),
              onToggleLanguage: widget.onToggleLanguage,
            );

        if (width >= 720) {
          return Scaffold(
            key: _scaffoldKey,
            body: Row(
              children: [
                sidebar(compact: width < 1100),
                Expanded(child: content),
              ],
            ),
          );
        }

        // Small screens: AppBar + Drawer.
        final title = switch (_destination) {
          SidebarDestination.products => s.inventoryTitle,
          SidebarDestination.addProduct => s.navAddProduct,
          SidebarDestination.editProduct => s.navEditProduct,
        };
        return Scaffold(
          key: _scaffoldKey,
          appBar: AppBar(
            backgroundColor: AppColors.sidebar,
            foregroundColor: Colors.white,
            title: Text(title,
                style: const TextStyle(fontWeight: FontWeight.w700)),
          ),
          drawer: Drawer(
            width: 260,
            backgroundColor: AppColors.sidebar,
            child: sidebar(compact: false),
          ),
          body: content,
        );
      },
    );
  }
}

class _MissingProduct extends StatelessWidget {
  const _MissingProduct({required this.onBack});

  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.search_off_rounded,
              size: 48, color: AppColors.textMuted),
          const SizedBox(height: AppSpacing.md),
          Text(s.productNotFound),
          const SizedBox(height: AppSpacing.lg),
          FilledButton(onPressed: onBack, child: Text(s.backToInventory)),
        ],
      ),
    );
  }
}
