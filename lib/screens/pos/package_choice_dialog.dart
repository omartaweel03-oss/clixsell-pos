import 'package:flutter/material.dart';

import '../../l10n/package_strings.dart';
import '../../models/product.dart';
import '../../models/product_package.dart';
import '../../utils/formatters.dart';

/// "Piece or carton?" for a product that is also sold in packages (feature 6). Returns the
/// chosen [ProductPackage], the string 'piece', or null when canceled.
class PackageChoiceDialog extends StatelessWidget {
  const PackageChoiceDialog({super.key, required this.product, required this.packages});

  final Product product;
  final List<ProductPackage> packages;

  @override
  Widget build(BuildContext context) {
    final t = PackageStrings.of(context);
    return SimpleDialog(
      key: const Key('packageChoiceDialog'),
      title: Text(product.name),
      children: [
        SimpleDialogOption(
          key: const Key('choosePiece'),
          onPressed: () => Navigator.pop(context, 'piece'),
          child: ListTile(
            leading: const Icon(Icons.looks_one_outlined),
            title: Text(t.piece),
            trailing: Text(Formatters.currency(product.price)),
          ),
        ),
        for (final p in packages)
          SimpleDialogOption(
            key: Key('choosePackage-${p.packageId}'),
            onPressed: () => Navigator.pop(context, p),
            child: ListTile(
              leading: const Icon(Icons.inventory_2_outlined),
              title: Text(p.name),
              subtitle: Text(t.piecesEach(p.unitsPerPackage)),
              trailing: Text(Formatters.currency(p.price)),
            ),
          ),
      ],
    );
  }
}
