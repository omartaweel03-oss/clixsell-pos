import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/product.dart';
import '../../services/api_http.dart';
import '../../state/product_store.dart';
import '../../theme/app_theme.dart';
import 'widgets/confirm_dialogs.dart';

/// Delete with confirmation and "Undo" – shared by the Products table and the
/// Product Details screen (Figure 3.4), so both behave the same:
/// confirm → DELETE /api/products/{id} → message with Undo (adds it again).
///
/// Returns true when the product was deleted.
Future<bool> deleteProductWithUndo(
  BuildContext context,
  Product product,
) async {
  final s = AppStrings.of(context);
  final store = ProductStoreScope.read(context);
  final messenger = ScaffoldMessenger.of(context);

  final confirmed = await confirmDeleteProduct(context, product);
  if (!confirmed || !context.mounted) return false;

  final index = store.products.indexWhere((p) => p.id == product.id);
  final Product? removed;
  try {
    removed = await store.deleteProduct(
      product.id,
    ); // DELETE /api/products/{id}
  } on ApiException catch (e) {
    _showError(messenger, s.deleteFailed(_describe(e, s)));
    return false;
  }
  if (removed == null) return false;
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
  return true;
}

String _describe(ApiException e, AppStrings s) =>
    e.isNetworkError ? s.networkError : e.message;

void _showError(ScaffoldMessengerState messenger, String message) {
  messenger
    ..hideCurrentSnackBar()
    ..showSnackBar(
      SnackBar(content: Text(message), backgroundColor: AppColors.danger),
    );
}
