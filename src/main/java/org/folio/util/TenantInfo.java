package org.folio.util;

public class TenantInfo {
  private String newTenantId;
  private boolean consortiaTenant;

  public String getNewTenantId() {
    return newTenantId;
  }

  public void setNewTenantId(String newTenantId) {
    this.newTenantId = newTenantId;
  }

  public boolean isConsortiaTenant() {
    return consortiaTenant;
  }

  public void setConsortiaTenant(boolean consortiaTenant) {
    this.consortiaTenant = consortiaTenant;
  }
}
