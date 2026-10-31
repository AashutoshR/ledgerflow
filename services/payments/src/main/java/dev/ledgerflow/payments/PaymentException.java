package dev.ledgerflow.payments;

public class PaymentException extends RuntimeException {
  final int status;
  final String code;

  PaymentException(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}
