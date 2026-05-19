package com.asm.delivery.config;

public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static void set(String companyId) { CURRENT.set(companyId); }
    public static String get()               { return CURRENT.get(); }
    public static void clear()               { CURRENT.remove(); }

    private TenantContext() {}
}
