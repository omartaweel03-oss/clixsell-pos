/// Product categories available in the Add / Edit Product dropdown.
///
/// Each category carries both an English and an Arabic label so the UI can
/// switch language (and text direction) without changing stored data.
enum ProductCategory {
  dairy('Dairy', 'منتجات الألبان'),
  bakery('Bakery', 'مخبوزات'),
  beverages('Beverages', 'مشروبات'),
  snacks('Snacks', 'تسالي ووجبات خفيفة'),
  groceries('Groceries', 'مواد غذائية'),
  freshProduce('Fresh Produce', 'منتجات طازجة'),
  frozen('Frozen Food', 'أغذية مجمدة'),
  cleaning('Cleaning Supplies', 'مواد تنظيف'),
  personalCare('Personal Care', 'عناية شخصية'),
  other('Other', 'أخرى');

  const ProductCategory(this.labelEn, this.labelAr);

  final String labelEn;
  final String labelAr;

  String label({required bool arabic}) => arabic ? labelAr : labelEn;
}
