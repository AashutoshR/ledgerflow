package dev.ledgerflow.walletledger;

import java.math.BigDecimal;

public final class Money {
  private Money() {}

  public static long parse(String value) {
    if (value == null || !value.matches("[0-9]{1,15}\\.[0-9]{2}"))
      throw new LedgerException(400, "INVALID_AMOUNT");
    try {
      long amount = new BigDecimal(value).movePointRight(2).longValueExact();
      if (amount <= 0) throw new ArithmeticException();
      return amount;
    } catch (ArithmeticException e) {
      throw new LedgerException(400, "INVALID_AMOUNT");
    }
  }

  public static String format(long minor) {
    return BigDecimal.valueOf(minor, 2).toPlainString();
  }
}
