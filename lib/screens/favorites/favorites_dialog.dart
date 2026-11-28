import 'package:flutter/material.dart';

import '../../l10n/favorite_strings.dart';
import '../../models/product.dart';
import '../../services/favorite_api_client.dart';
import '../../theme/app_theme.dart';
import '../shifts/shift_dialogs.dart' show shiftError;

/// Store Admin: choose and order the POS favorites. Saved as ONE complete list (the backend
/// replaces the old one in a single transaction), so a failed save changes nothing.
class FavoritesDialog extends StatefulWidget {
  const FavoritesDialog({super.key, required this.api, required this.products});

  final FavoriteApiClient api;
  final List<Product> products;

  static Future<bool?> show(BuildContext context, FavoriteApiClient api, List<Product> products) =>
      showDialog<bool>(
        context: context,
        barrierDismissible: false,
        builder: (_) => FavoritesDialog(api: api, products: products),
      );

  @override
  State<FavoritesDialog> createState() => _FavoritesDialogState();
}

class _FavoritesDialogState extends State<FavoritesDialog> {
  List<String>? _ids;
  String? _adding;
  bool _busy = false;
  String? _error;

  late final Map<String, Product> _byId = {for (final p in widget.products) p.id: p};

  @override
  void initState() {
    super.initState();
    widget.api.list().then((ids) {
      if (mounted) setState(() => _ids = ids.where(_byId.containsKey).toList());
    }, onError: (Object e) {
      if (mounted) setState(() => _error = shiftError(context, e));
    });
  }

  void _move(int i, int d) {
    final ids = _ids!;
    final j = i + d;
    if (j < 0 || j >= ids.length) return;
    setState(() {
      final x = ids.removeAt(i);
      ids.insert(j, x);
    });
  }

  Future<void> _save() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.api.save(_ids!);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = shiftError(context, e);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = FavoriteStrings.of(context);
    final ids = _ids;
    final available = [
      for (final p in widget.products)
        if (ids != null && !ids.contains(p.id)) p,
    ]..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    final full = ids != null && ids.length >= FavoriteApiClient.maxFavorites;
    return AlertDialog(
      key: const Key('favoritesDialog'),
      title: Text(t.title),
      content: SizedBox(
        width: 480,
        height: 460,
        child: ids == null
            ? Center(
                child: _error == null
                    ? const CircularProgressIndicator()
                    : Text(_error!, style: const TextStyle(color: AppColors.danger)),
              )
            : Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(t.description, style: const TextStyle(color: AppColors.textSecondary)),
                  const SizedBox(height: AppSpacing.md),
                  Row(children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        key: const Key('favoriteAddField'),
                        initialValue: _adding,
                        isExpanded: true,
                        decoration: InputDecoration(labelText: t.add),
                        items: [
                          for (final p in available)
                            DropdownMenuItem(value: p.id, child: Text(p.name, overflow: TextOverflow.ellipsis)),
                        ],
                        onChanged: _busy || full ? null : (v) => setState(() => _adding = v),
                      ),
                    ),
                    const SizedBox(width: AppSpacing.sm),
                    IconButton.filled(
                      key: const Key('favoriteAddButton'),
                      tooltip: t.add,
                      icon: const Icon(Icons.add_rounded),
                      onPressed: _busy || full || _adding == null
                          ? null
                          : () => setState(() {
                              ids.add(_adding!);
                              _adding = null;
                            }),
                    ),
                  ]),
                  if (full)
                    Text(t.max(FavoriteApiClient.maxFavorites), style: const TextStyle(color: AppColors.warning)),
                  const SizedBox(height: AppSpacing.sm),
                  Expanded(
                    child: ids.isEmpty
                        ? Center(child: Text(t.empty, key: const Key('favoritesEmpty')))
                        : ReorderableListView(
                            buildDefaultDragHandles: false,
                            onReorderItem: (a, b) => setState(() {
                              ids.insert(b, ids.removeAt(a));
                            }),
                            children: [
                              for (var i = 0; i < ids.length; i++)
                                ListTile(
                                  key: Key('favorite-${ids[i]}'),
                                  leading: ReorderableDragStartListener(
                                    index: i,
                                    child: const Icon(Icons.drag_indicator_rounded),
                                  ),
                                  title: Text('${i + 1}. ${_byId[ids[i]]!.name}'),
                                  trailing: Wrap(children: [
                                    IconButton(
                                      key: Key('favoriteUp-${ids[i]}'),
                                      tooltip: t.moveUp,
                                      icon: const Icon(Icons.arrow_upward_rounded),
                                      onPressed: i == 0 || _busy ? null : () => _move(i, -1),
                                    ),
                                    IconButton(
                                      key: Key('favoriteDown-${ids[i]}'),
                                      tooltip: t.moveDown,
                                      icon: const Icon(Icons.arrow_downward_rounded),
                                      onPressed: i == ids.length - 1 || _busy ? null : () => _move(i, 1),
                                    ),
                                    IconButton(
                                      key: Key('favoriteRemove-${ids[i]}'),
                                      tooltip: t.remove,
                                      icon: const Icon(Icons.close_rounded),
                                      onPressed: _busy ? null : () => setState(() => ids.removeAt(i)),
                                    ),
                                  ]),
                                ),
                            ],
                          ),
                  ),
                  if (_error != null)
                    Text(_error!, key: const Key('favoritesError'), style: const TextStyle(color: AppColors.danger)),
                ],
              ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: Text(t.cancel),
        ),
        FilledButton(
          key: const Key('favoritesSave'),
          onPressed: _busy || ids == null ? null : _save,
          child: Text(t.save),
        ),
      ],
    );
  }
}

/// The favorites on the POS screen: one tap adds the product to the invoice.
class PosFavoritesStrip extends StatelessWidget {
  const PosFavoritesStrip({super.key, required this.products, required this.onAdd});

  /// Favorites that still exist, in the Store Admin's order.
  final List<Product> products;
  final ValueChanged<Product> onAdd;

  @override
  Widget build(BuildContext context) {
    if (products.isEmpty) return const SizedBox.shrink();
    final t = FavoriteStrings.of(context);
    return Padding(
      key: const Key('posFavorites'),
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: Row(
        children: [
          const Icon(Icons.star_rounded, color: AppColors.warning, size: 20),
          const SizedBox(width: AppSpacing.xs),
          Text(t.strip, style: const TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  for (final p in products)
                    Padding(
                      padding: const EdgeInsetsDirectional.only(end: AppSpacing.sm),
                      child: ActionChip(
                        key: Key('posFavorite-${p.id}'),
                        avatar: p.quantity <= 0
                            ? const Icon(Icons.block_rounded, size: 16, color: AppColors.danger)
                            : null,
                        label: Text(p.name),
                        onPressed: () => onAdd(p),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
