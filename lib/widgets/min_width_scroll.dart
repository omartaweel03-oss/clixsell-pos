import 'package:flutter/material.dart';

/// Keeps a desktop layout (a table, the POS columns) at least [minWidth]
/// wide. When the screen is narrower the layout scrolls sideways instead of
/// being squeezed or replaced by a different (mobile) design.
class MinWidthScroll extends StatelessWidget {
  const MinWidthScroll({
    super.key,
    required this.minWidth,
    required this.child,
    this.fillHeight = false,
  });

  final double minWidth;
  final Widget child;

  /// Give the child the full available height (for page-sized layouts).
  final bool fillHeight;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, c) {
      if (c.maxWidth >= minWidth) return child;
      return Scrollbar(
        child: SingleChildScrollView(
          key: const Key('minWidthScroll'),
          scrollDirection: Axis.horizontal,
          child: SizedBox(
            width: minWidth,
            height: fillHeight ? c.maxHeight : null,
            child: child,
          ),
        ),
      );
    },
  );
}
