package com.crystalgraphics.platform.rhi.resource;


import com.crystalgraphics.platform.rhi.CgRHI;
/** Something a {@link CgRHI} created. Freed by {@link CgRHI#release}, never directly. */
public interface CgRHIResource {

    /** The name debug tools show, from the creating call. */
    String label();
}
