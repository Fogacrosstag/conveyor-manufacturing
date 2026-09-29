package com.conveyor.platform;

public class Fault extends RuntimeException {
  public final int status;
  public final String code;

  public Fault(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
