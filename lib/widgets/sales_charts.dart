import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import '../utils/formatters.dart';

/// Simple charts drawn with [CustomPaint] (no extra package): a line chart
/// for daily sales, a bar chart for monthly sales and a donut chart for the
/// best-selling products (report Figures 3.7 / 3.8).
///
/// Charts always run left-to-right (days and months grow to the right) even
/// in Arabic, like the report's figures.

/// Palette of the donut chart (same order as its legend).
const chartPalette = <Color>[
  Color(0xFF7C6CF2),
  Color(0xFFF0958A),
  Color(0xFF5BC0DE),
  Color(0xFFF2AE5B),
  Color(0xFF5B7FE8),
  Color(0xFF8FD3A5),
  Color(0xFF8A6AD6),
  Color(0xFFB8BED6), // "others"
];

String _axisLabel(double v) {
  final a = v.abs();
  if (a >= 1000000) return '${(v / 1000000).toStringAsFixed(1)}M';
  if (a >= 10000) return '${(v / 1000).toStringAsFixed(0)}K';
  if (a >= 1000) return '${(v / 1000).toStringAsFixed(1)}K';
  return v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(1);
}

/// A "nice" upper bound for the y axis (1, 2, 2.5, 5 × 10ⁿ steps).
double _niceMax(double v) {
  if (v <= 0) return 4;
  final exp = math.pow(10, (math.log(v) / math.ln10).floor()).toDouble();
  for (final m in [1.0, 2.0, 2.5, 5.0, 10.0]) {
    if (m * exp >= v) return m * exp;
  }
  return 10 * exp;
}

class _Axis {
  _Axis(List<double> values) {
    final maxV = values.fold<double>(0, math.max);
    final minV = values.fold<double>(0, math.min);
    top = _niceMax(maxV);
    bottom = minV < 0 ? -_niceMax(-minV) : 0;
  }

  late final double top;
  late final double bottom;

  double get range => top - bottom == 0 ? 1 : top - bottom;
}

void _drawGrid(Canvas canvas, Rect plot, _Axis axis) {
  final grid = Paint()
    ..color = AppColors.divider
    ..strokeWidth = 1;
  const steps = 4;
  for (var i = 0; i <= steps; i++) {
    final v = axis.bottom + axis.range * i / steps;
    final y = plot.bottom - plot.height * i / steps;
    canvas.drawLine(Offset(plot.left, y), Offset(plot.right, y), grid);
    _text(
      canvas,
      _axisLabel(v),
      Offset(plot.left - 6, y),
      align: _Align.right,
      size: 10,
    );
  }
  if (axis.bottom < 0) {
    final y0 = plot.bottom - plot.height * (0 - axis.bottom) / axis.range;
    canvas.drawLine(
      Offset(plot.left, y0),
      Offset(plot.right, y0),
      Paint()
        ..color = AppColors.textMuted
        ..strokeWidth = 1,
    );
  }
}

enum _Align { left, center, right }

void _text(
  Canvas canvas,
  String text,
  Offset at, {
  _Align align = _Align.center,
  double size = 10,
  Color color = AppColors.textSecondary,
  FontWeight weight = FontWeight.w500,
  bool above = false,
}) {
  final tp = TextPainter(
    text: TextSpan(
      text: text,
      style: TextStyle(color: color, fontSize: size, fontWeight: weight),
    ),
    textDirection: TextDirection.ltr,
  )..layout();
  final dx = switch (align) {
    _Align.left => at.dx,
    _Align.center => at.dx - tp.width / 2,
    _Align.right => at.dx - tp.width,
  };
  final dy = above ? at.dy - tp.height : at.dy - tp.height / 2;
  tp.paint(canvas, Offset(dx, dy));
}

/// Line chart with a filled area (daily sales).
class SalesLineChart extends StatelessWidget {
  const SalesLineChart({
    super.key,
    required this.values,
    required this.labels,
    this.color = AppColors.primary,
    this.semanticLabel,
  });

  final List<double> values;
  final List<String> labels;
  final Color color;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) => Semantics(
    label: semanticLabel,
    child: Directionality(
      textDirection: TextDirection.ltr,
      child: CustomPaint(
        painter: _LinePainter(values, labels, color),
        size: Size.infinite,
      ),
    ),
  );
}

class _LinePainter extends CustomPainter {
  _LinePainter(this.values, this.labels, this.color);

  final List<double> values;
  final List<String> labels;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    if (values.isEmpty) return;
    final axis = _Axis(values);
    final plot = Rect.fromLTRB(42, 18, size.width - 12, size.height - 22);
    _drawGrid(canvas, plot, axis);

    double x(int i) => values.length == 1
        ? plot.center.dx
        : plot.left + plot.width * i / (values.length - 1);
    double y(double v) =>
        plot.bottom - plot.height * (v - axis.bottom) / axis.range;

    final line = Path();
    for (var i = 0; i < values.length; i++) {
      i == 0
          ? line.moveTo(x(i), y(values[i]))
          : line.lineTo(x(i), y(values[i]));
    }
    final baseY = y(math.max(axis.bottom, 0));
    final area = Path.from(line)
      ..lineTo(x(values.length - 1), baseY)
      ..lineTo(x(0), baseY)
      ..close();
    canvas.drawPath(area, Paint()..color = color.withValues(alpha: 0.12));
    canvas.drawPath(
      line,
      Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..strokeJoin = StrokeJoin.round,
    );

    // Points and value labels (only where there is something to show).
    final showAll = values.length <= 12;
    final labelEvery = (values.length / 8).ceil().clamp(1, 1000);
    for (var i = 0; i < values.length; i++) {
      final p = Offset(x(i), y(values[i]));
      if (values[i] != 0 || showAll) {
        canvas.drawCircle(p, 3.2, Paint()..color = Colors.white);
        canvas.drawCircle(
          p,
          3.2,
          Paint()
            ..color = color
            ..style = PaintingStyle.stroke
            ..strokeWidth = 1.6,
        );
      }
      if (values[i] != 0 && values.length <= 16) {
        _text(
          canvas,
          _axisLabel(values[i]),
          p.translate(0, -6),
          size: 9,
          above: true,
        );
      }
      if (i == values.length - 1 ||
          (i % labelEvery == 0 &&
              values.length - 1 - i >= (labelEvery + 1) ~/ 2)) {
        _text(canvas, labels[i], Offset(p.dx, plot.bottom + 12), size: 10);
      }
    }
  }

  @override
  bool shouldRepaint(_LinePainter old) =>
      old.values != values || old.labels != labels || old.color != color;
}

/// Bar chart (monthly sales).
class SalesBarChart extends StatelessWidget {
  const SalesBarChart({
    super.key,
    required this.values,
    required this.labels,
    this.color = AppColors.primary,
    this.highlight,
    this.semanticLabel,
  });

  final List<double> values;
  final List<String> labels;
  final Color color;

  /// Index of the bar drawn darker (the selected month).
  final int? highlight;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) => Semantics(
    label: semanticLabel,
    child: Directionality(
      textDirection: TextDirection.ltr,
      child: CustomPaint(
        painter: _BarPainter(values, labels, color, highlight),
        size: Size.infinite,
      ),
    ),
  );
}

class _BarPainter extends CustomPainter {
  _BarPainter(this.values, this.labels, this.color, this.highlight);

  final List<double> values;
  final List<String> labels;
  final Color color;
  final int? highlight;

  @override
  void paint(Canvas canvas, Size size) {
    if (values.isEmpty) return;
    final axis = _Axis(values);
    final plot = Rect.fromLTRB(42, 18, size.width - 8, size.height - 22);
    _drawGrid(canvas, plot, axis);
    final slot = plot.width / values.length;
    final barW = math.min(26.0, slot * 0.62);
    double y(double v) =>
        plot.bottom - plot.height * (v - axis.bottom) / axis.range;
    final zeroY = y(0);
    for (var i = 0; i < values.length; i++) {
      final cx = plot.left + slot * (i + 0.5);
      final top = y(values[i]);
      final rect = Rect.fromLTRB(
        cx - barW / 2,
        math.min(top, zeroY),
        cx + barW / 2,
        math.max(top, zeroY),
      );
      final c = highlight == null || highlight == i
          ? color
          : color.withValues(alpha: 0.45);
      if (rect.height > 0) {
        canvas.drawRRect(
          RRect.fromRectAndCorners(
            rect,
            topLeft: values[i] >= 0 ? const Radius.circular(6) : Radius.zero,
            topRight: values[i] >= 0 ? const Radius.circular(6) : Radius.zero,
            bottomLeft: values[i] < 0 ? const Radius.circular(6) : Radius.zero,
            bottomRight: values[i] < 0 ? const Radius.circular(6) : Radius.zero,
          ),
          Paint()..color = c,
        );
      }
      if (values[i] != 0) {
        _text(
          canvas,
          _axisLabel(values[i]),
          Offset(cx, math.min(top, zeroY) - 3),
          size: 9,
          above: true,
        );
      }
      _text(canvas, labels[i], Offset(cx, plot.bottom + 12), size: 10);
    }
  }

  @override
  bool shouldRepaint(_BarPainter old) =>
      old.values != values ||
      old.labels != labels ||
      old.highlight != highlight;
}

/// Donut chart (share of the best-selling products).
class SalesDonutChart extends StatelessWidget {
  const SalesDonutChart({super.key, required this.values, this.semanticLabel});

  final List<double> values;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) => Semantics(
    label: semanticLabel,
    child: CustomPaint(painter: _DonutPainter(values), size: Size.infinite),
  );
}

class _DonutPainter extends CustomPainter {
  _DonutPainter(this.values);

  final List<double> values;

  @override
  void paint(Canvas canvas, Size size) {
    final total = values.fold<double>(0, (a, b) => a + math.max(b, 0));
    final r = math.min(size.width, size.height) / 2 - 4;
    final center = size.center(Offset.zero);
    final rect = Rect.fromCircle(center: center, radius: r);
    final stroke = r * 0.42;
    final arcRect = rect.deflate(stroke / 2);
    if (total <= 0) {
      canvas.drawArc(
        arcRect,
        0,
        math.pi * 2,
        false,
        Paint()
          ..color = AppColors.divider
          ..style = PaintingStyle.stroke
          ..strokeWidth = stroke,
      );
      return;
    }
    var start = -math.pi / 2;
    for (var i = 0; i < values.length; i++) {
      if (values[i] <= 0) continue;
      final sweep = math.pi * 2 * values[i] / total;
      canvas.drawArc(
        arcRect,
        start,
        sweep,
        false,
        Paint()
          ..color = chartPalette[i % chartPalette.length]
          ..style = PaintingStyle.stroke
          ..strokeWidth = stroke,
      );
      // thin white gap between segments
      canvas.drawLine(
        center + Offset(math.cos(start), math.sin(start)) * (r - stroke),
        center + Offset(math.cos(start), math.sin(start)) * r,
        Paint()
          ..color = Colors.white
          ..strokeWidth = 2,
      );
      start += sweep;
    }
  }

  @override
  bool shouldRepaint(_DonutPainter old) => old.values != values;
}

/// "+12.5% vs last month" line used by the report cards.
class ChangeLine extends StatelessWidget {
  const ChangeLine({super.key, required this.percent, required this.suffix});

  final double? percent;
  final String suffix;

  @override
  Widget build(BuildContext context) {
    final p = percent;
    if (p == null) {
      return Text(
        suffix,
        style: const TextStyle(color: AppColors.textMuted, fontSize: 12),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      );
    }
    final up = p >= 0;
    final c = up ? AppColors.success : AppColors.danger;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(
          up ? Icons.arrow_upward_rounded : Icons.arrow_downward_rounded,
          size: 14,
          color: c,
        ),
        const SizedBox(width: 2),
        Text(
          '${Formatters.number(p.abs(), decimals: 1)}%',
          style: TextStyle(color: c, fontSize: 12, fontWeight: FontWeight.w700),
        ),
        const SizedBox(width: 4),
        Flexible(
          child: Text(
            suffix,
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 12,
            ),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ],
    );
  }
}
