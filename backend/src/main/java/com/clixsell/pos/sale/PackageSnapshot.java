package com.clixsell.pos.sale;

/**
 * A product package (carton / box) as it is when sold (feature 6): its size and price are copied
 * onto the invoice line, so later changes to the package never change past sales.
 */
public record PackageSnapshot(long packageId, String name, int unitsPerPackage, double price) {
}
