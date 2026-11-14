/**
 * Materials: {@link com.crystalgraphics.api.material.CgMaterial}, a shared material made from a {@code .shader} or a
 * {@code .material} file and cached, and {@link com.crystalgraphics.api.material.CgMaterialInstance}, values of your own
 * over one. A draw takes either, as a {@link com.crystalgraphics.api.material.CgMaterialInterface}.
 *
 * <pre>{@code
 * CgMaterial terrain = CgMaterial.load("mymod:shaders/terrain.shader");        // the same object every call
 * CgMaterialInstance red = terrain.instance().vec4("_Color", 1f, 0f, 0f, 1f);  // once, kept in a field
 *
 * CgScene.client().mesh(mesh, terrain).at(x, y, z).spawn();                    // once: it draws until removed
 * CgScene.client().once().mesh(mesh, red).at(x, y + 2, z).add();               // this frame only
 * }</pre>
 *
 * <ul>
 *   <li>A keyword is another material: {@code terrain.keyword("MOSS", true)}, cached.</li>
 *   <li>Nothing a factory hands you is yours to free; an instance is: {@code red.delete()}.</li>
 * </ul>
 */
package com.crystalgraphics.api.material;
