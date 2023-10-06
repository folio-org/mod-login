package org.folio.util;
public class TokenNotFoundException extends RuntimeException {
  public TokenNotFoundException() {
    super("Token endpoint is not available");
  }
}
