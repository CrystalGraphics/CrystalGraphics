package com.crystalgraphics.mc.container;

/**
 * Which {@link Action}s a version's host can carry; {@code Click} and {@code Close} every host does.
 *
 * <pre>{@code
 * if (CgContainers.capabilities().rename()) showNameField();
 * }</pre>
 *
 * @param button      {@link Action.Button}: on 1.7.10 to 1.12.2 the enchanting table's options only, from 1.13 every menu's
 * @param rename      {@link Action.Rename}: modern 1.14.3 and later
 * @param selectTrade {@link Action.SelectTrade}: modern 1.14.3 and later
 * @param state       the open menu's kind has a {@link CgContainers.Menu#state} extractor on this version
 */
public record Capabilities(boolean button, boolean rename, boolean selectTrade, boolean state) {

    /** A host that can only click and close. */
    public static final Capabilities NONE = new Capabilities(false, false, false, false);
}
