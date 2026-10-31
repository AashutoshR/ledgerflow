package dev.ledgerflow.walletledger;

public class LedgerException extends RuntimeException {
  final int status;
  final String code;

  LedgerException(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}
