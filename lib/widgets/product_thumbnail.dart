import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Square product image. Shows the uploaded image bytes, or a neutral
/// Material icon placeholder when the product has no image.
class ProductThumbnail extends StatelessWidget {
  const ProductThumbnail({
    super.key,
    required this.imageBytes,
    this.size = 44,
    this.radius = AppSpacing.radiusSm,
  });

  final Uint8List? imageBytes;
  final double size;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final bytes = imageBytes;
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: AppColors.primarySoft,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: AppColors.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: bytes != null && bytes.isNotEmpty
          ? Image.memory(
              bytes,
              fit: BoxFit.cover,
              gaplessPlayback: true,
              errorBuilder: (context, error, stackTrace) => _placeholder(),
            )
          : _placeholder(),
    );
  }

  Widget _placeholder() => Icon(
        Icons.inventory_2_outlined,
        color: AppColors.primary.withValues(alpha: 0.7),
        size: size.isFinite ? size * 0.5 : 48,
      );
}
