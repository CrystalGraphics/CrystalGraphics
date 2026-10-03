package com.crystalgraphics.compute.source;

/**
 * The code of a {@code .compute}, in order, cut where a kernel's source differs from its neighbour's: each kernel is
 * emitted from the same parts, keeping only the functions and {@code shared} variables it reaches.
 */
public sealed interface CgSourcePart {

    /** Text every kernel carries as written: structs, constants, preprocessor lines. */
    record Text(String text) implements CgSourcePart {}

    /** A function definition at file scope. */
    record Function(String name, String text) implements CgSourcePart {}

    /** A {@code shared} variable, which only a kernel that reaches it declares. */
    record Shared(String name, String text) implements CgSourcePart {}

    /** Where the {@code Buffers} block stood: the generated declarations go there, after the structs above it. */
    record Buffers() implements CgSourcePart {}

    /** Where the {@code Images} block stood. */
    record Images() implements CgSourcePart {}
}
