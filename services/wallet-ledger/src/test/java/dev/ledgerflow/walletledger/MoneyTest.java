package dev.ledgerflow.walletledger;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MoneyTest {
  @Test
  void exactMinorUnits() {
    assertThat(Money.parse("10.25")).isEqualTo(1025);
    assertThat(Money.format(1025)).isEqualTo("10.25");
  }

  @Test
  void rejectsInvalidPrecisionAndNonPositiveAmounts() {
    for (String value :
        new String[] {"0.00", "-1.00", "1.001", "1e2", "NaN", "1", "1.0", "9999999999999999.00"})
      assertThatThrownBy(() -> Money.parse(value)).isInstanceOf(LedgerException.class);
  }
}
