package org.folio.util;
public class TokenEndpointNotFoundException extends RuntimeException {
  public TokenEndpointNotFoundException() {
    super("Token endpoint is not available");
  }
}
