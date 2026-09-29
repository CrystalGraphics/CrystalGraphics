/**
 * Where a tracked GL buffer's bytes live: {@code CgTrackedBuffer}, whose storage is renamed rather than waited on,
 * over {@code CgSlabAllocator}'s sub-allocations of device buffers.
 *
 * <p>Public for the tracker and the backend, and not an API.
 */
package com.crystalgraphics.platform.gl.tracked.memory;
