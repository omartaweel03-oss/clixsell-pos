# Bundled fonts (offline rendering)

The web / desktop app ships its own fonts so that English and Arabic text render without any
request to an external font service (the app works on a store computer with no internet).

| Family | Files | Source | License |
|---|---|---|---|
| Noto Sans Arabic | `NotoSansArabic/NotoSansArabic-400.ttf` (regular only: the same weight Flutter web downloaded before, bold is synthesized, so Arabic widths and line breaks stay as they were) | Google Fonts (static instances of Noto Sans Arabic, notofonts/arabic) | SIL Open Font License 1.1 — `NotoSansArabic/OFL.txt` |
| Roboto | `Roboto/Roboto-{400,500,600,700,800}.ttf` | Google Fonts (static instances of Roboto, googlefonts/roboto-classic) | SIL Open Font License 1.1 — `Roboto/OFL.txt` |

The fonts are redistributed unmodified under the OFL (permitted when bundled with software,
with the license text and copyright notice kept, as in the two `OFL.txt` files). The font names
are not used to sell the fonts by themselves. Only the weights the app uses are included.
Emoji are not bundled (Noto Color Emoji is ~4.7 MB): an emoji in a product name may still be
fetched by Flutter web when online, and shows as a box offline.
