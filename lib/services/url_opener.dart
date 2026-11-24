import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

/// Opens an external link (the supplier's WhatsApp link) — decisions D20 / P4-D1.
///
/// Only links returned by the backend after a confirmed opening request are
/// passed here. The result only says whether the browser / operating system
/// accepted the link: it is NOT proof that WhatsApp opened, or that a message
/// was sent, received or read.
abstract class UrlOpener {
  Future<bool> open(String url);
}

class LauncherUrlOpener implements UrlOpener {
  const LauncherUrlOpener();

  @override
  Future<bool> open(String url) async {
    final uri = Uri.tryParse(url);
    // Defence in depth: the backend only returns https://wa.me/ links.
    if (uri == null || uri.scheme != 'https' || uri.host != 'wa.me') return false;
    try {
      return await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      return false;
    }
  }
}

/// Copies text to the clipboard. Returns false when the platform refused
/// (e.g. the browser did not allow clipboard access).
abstract class ClipboardWriter {
  Future<bool> copy(String text);
}

class SystemClipboardWriter implements ClipboardWriter {
  const SystemClipboardWriter();

  @override
  Future<bool> copy(String text) async {
    try {
      await Clipboard.setData(ClipboardData(text: text));
      return true;
    } catch (_) {
      return false;
    }
  }
}
