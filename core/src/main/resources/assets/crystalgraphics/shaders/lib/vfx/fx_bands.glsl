// Flat bands of colour by how near the view ray passes a shape's axis or centre: a toon energy body's look.
// b is that nearness as a share of the shape's size, w its width across a pixel (fwidth(b)), so each edge is
// antialiased over one pixel and no more.

// How much of the band past edge covers b: 0 inside it, 1 past it.
float fx_band_step(float b, float edge, float w) {
    return smoothstep(edge - w, edge + w, b);
}

// Up to three bands, c0 innermost, each band's outer edge in edges, ascending. Straight colour, its alpha the
// band's alpha times its coverage at the outermost edge; glow takes the glow of the band b falls in, from glows.
vec4 fx_bands(float b, float w, vec4 c0, vec4 c1, vec4 c2, vec3 edges, int count, vec3 glows, out float glow) {
    vec4 c = c0;
    glow = glows.x;
    if (count > 1) {
        float t = fx_band_step(b, edges.x, w);
        c = mix(c, c1, t);
        glow = mix(glow, glows.y, t);
    }
    if (count > 2) {
        float t = fx_band_step(b, edges.y, w);
        c = mix(c, c2, t);
        glow = mix(glow, glows.z, t);
    }
    float outer = count > 2 ? edges.z : count > 1 ? edges.y : edges.x;
    c.a *= 1.0 - fx_band_step(b, outer, w);
    return c;
}

// A halo round the bands: whole at their outer edge, gone at the shape's size (b = 1); soft above 1 keeps it close.
float fx_band_halo(float b, float outer, float soft) {
    return pow(1.0 - smoothstep(outer, 1.0, b), soft);
}
