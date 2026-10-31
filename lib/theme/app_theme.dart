import 'package:flutter/material.dart';

/// Central colour palette for ClixSell POS.
///
/// Based on the Figma screens: dark navy sidebar, purple primary actions,
/// light grey/white content area, and soft tinted statistic cards.
class AppColors {
  AppColors._();

  // Brand
  static const Color primary = Color(0xFF6C5CE7); // purple – primary actions
  static const Color primaryDark = Color(0xFF5A4BD1);
  static const Color primarySoft = Color(0xFFEDEBFF);

  // Sidebar
  static const Color sidebar = Color(0xFF14204A); // dark navy
  static const Color sidebarText = Color(0xFFC9CFE8);
  static const Color sidebarMuted = Color(0xFF7F89B0);

  // Surfaces
  static const Color background = Color(0xFFF4F6FB);
  static const Color surface = Colors.white;
  static const Color border = Color(0xFFE4E7F0);
  static const Color divider = Color(0xFFEEF0F6);

  // Text
  static const Color textPrimary = Color(0xFF1C2340);
  static const Color textSecondary = Color(0xFF6B7393);
  static const Color textMuted = Color(0xFF9AA1BC);

  // Status colours
  static const Color success = Color(0xFF22A06B);
  static const Color successSoft = Color(0xFFE6F6EE);
  static const Color warning = Color(0xFFE8890C);
  static const Color warningSoft = Color(0xFFFFF3E0);
  static const Color danger = Color(0xFFE5484D);
  static const Color dangerSoft = Color(0xFFFDECEC);
  static const Color info = Color(0xFF4C6FFF);
  static const Color infoSoft = Color(0xFFEAF0FF);
}

/// Shared spacing / radius tokens so every widget lines up consistently.
class AppSpacing {
  AppSpacing._();

  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 24;
  static const double xxl = 32;

  static const double radiusSm = 8;
  static const double radiusMd = 12;
  static const double radiusLg = 16;
}

class AppTheme {
  AppTheme._();

  static ThemeData light() {
    final base = ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(
        seedColor: AppColors.primary,
        primary: AppColors.primary,
        surface: AppColors.surface,
        error: AppColors.danger,
      ),
      scaffoldBackgroundColor: AppColors.background,
      // Fallback fonts make sure Arabic glyphs render on every platform.
      fontFamilyFallback: const ['Segoe UI', 'Geeza Pro', 'Noto Sans Arabic', 'Arial'],
    );

    final outline = OutlineInputBorder(
      borderRadius: BorderRadius.circular(AppSpacing.radiusSm + 2),
      borderSide: const BorderSide(color: AppColors.border),
    );

    return base.copyWith(
      textTheme: base.textTheme.apply(
        bodyColor: AppColors.textPrimary,
        displayColor: AppColors.textPrimary,
      ),
      dividerColor: AppColors.divider,
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: AppColors.surface,
        isDense: true,
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        hintStyle: const TextStyle(color: AppColors.textMuted, fontSize: 14),
        border: outline,
        enabledBorder: outline,
        focusedBorder: outline.copyWith(
          borderSide: const BorderSide(color: AppColors.primary, width: 1.6),
        ),
        errorBorder: outline.copyWith(
          borderSide: const BorderSide(color: AppColors.danger),
        ),
        focusedErrorBorder: outline.copyWith(
          borderSide: const BorderSide(color: AppColors.danger, width: 1.6),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: AppColors.primary,
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
          textStyle: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(AppSpacing.radiusSm + 2),
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: AppColors.textPrimary,
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
          side: const BorderSide(color: AppColors.border),
          textStyle: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(AppSpacing.radiusSm + 2),
          ),
        ),
      ),
      cardTheme: CardThemeData(
        color: AppColors.surface,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
          side: const BorderSide(color: AppColors.border),
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: AppColors.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: AppColors.textPrimary,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        ),
      ),
    );
  }
}
