package com.clixsell.pos.supplier;

import com.clixsell.pos.product.Product;

import java.util.List;

/**
 * A supplier as shown on the Supplier Management screen (Store Admin only).
 * {@code products} is filled only for the details request.
 */
public record SupplierResponse(
        Long supplierId,
        String name,
        String phone,
        String whatsappNumber,
        String address,
        boolean active,
        long productCount,
        List<SupplierProduct> products) {

    /** A product that uses this supplier. */
    public record SupplierProduct(Long productId, String name, String barcode, int quantity, int minStock) {
        static SupplierProduct from(Product p) {
            return new SupplierProduct(p.getProductId(), p.getName(), p.getBarcode(), p.getQuantity(), p.getMinStock());
        }
    }

    public static SupplierResponse summary(SupplierService.SupplierSummary s) {
        Supplier x = s.supplier();
        return new SupplierResponse(x.getSupplierId(), x.getName(), x.getPhone(), x.getWhatsappNumber(), x.getAddress(),
                x.isActive(), s.productCount(), null);
    }

    public static SupplierResponse details(Supplier x, List<Product> products) {
        return new SupplierResponse(x.getSupplierId(), x.getName(), x.getPhone(), x.getWhatsappNumber(), x.getAddress(),
                x.isActive(), products.size(), products.stream().map(SupplierProduct::from).toList());
    }
}
