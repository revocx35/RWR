package com.google.android.datatransport.cct;

import com.google.android.datatransport.runtime.Destination;

/**
 * RWR: stand-in for Google's Clearcut (CCT) destination. It has no address: the stand-in
 * {@link com.google.android.datatransport.runtime.TransportRuntime} drops every event anyway.
 */
public final class CCTDestination implements Destination {
    public static final CCTDestination INSTANCE = new CCTDestination();
    public static final CCTDestination LEGACY_INSTANCE = INSTANCE;

    private CCTDestination() {}

    @Override public String getName() {
        return "disabled";
    }

    @Override public byte[] getExtras() {
        return null;
    }
}
