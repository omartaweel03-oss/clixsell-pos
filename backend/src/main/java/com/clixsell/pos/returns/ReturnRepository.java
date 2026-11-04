package com.clixsell.pos.returns;

import org.springframework.data.jpa.repository.JpaRepository;

/** Database access for returns. */
public interface ReturnRepository extends JpaRepository<ReturnProduct, Long> {
}
