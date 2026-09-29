package com.conveyor.material;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MaterialRulesTest {
  @Test
  void fractionalQuantitiesDoNotOverReserve() {
    assertTrue(
        Materials.canReserve(new BigDecimal("0.3"), new BigDecimal("0.1"), new BigDecimal("0.2")));
    assertFalse(
        Materials.canReserve(
            new BigDecimal("0.3"), new BigDecimal("0.1"), new BigDecimal("0.2001")));
  }

  @Test
  void zeroAndNegativeRequestsAreRejected() {
    assertFalse(Materials.canReserve(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO));
    assertFalse(Materials.canReserve(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE.negate()));
  }
}
