package com.crystalgraphics.platform.device;

/** Something a {@link CgDevice} created. Freed by {@link CgDevice#release}, never directly. */
public interface CgDeviceObject {

    /** The name debug tools show, from the creating call. */
    String label();
}
