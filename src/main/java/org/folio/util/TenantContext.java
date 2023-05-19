package org.folio.util;

public class TenantContext {
  private String newTenantId;
  private boolean crossTenantUser;

  public String getNewTenantId() {
    return newTenantId;
  }

  public void setNewTenantId(String newTenantId) {
    this.newTenantId = newTenantId;
  }

  public boolean isCrossTenantUser() {
    return crossTenantUser;
  }

  public void setCrossTenantUser(boolean crossTenantUser) {
    this.crossTenantUser = crossTenantUser;
  }
}
