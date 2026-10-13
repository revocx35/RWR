package com.google.android.datatransport.runtime;

/** RWR: stand-in for the datatransport runtime's Destination (see {@link TransportRuntime}). */
public interface Destination {
    String getName();

    byte[] getExtras();
}
