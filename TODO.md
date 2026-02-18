# TODO

Graphics abstraction layer for OpenGL LWJGL 2.9

Main usage: Minecraft 1.7.10 mods, however it would be good if the library could remain Minecraft/Forge agnostic. 

# Requirements

## High priority
### Framebuffer abstractions
We need a nice way to create and handle FBOs from different extensions (Core, ARB, EXT) depending on the capabilities they support:
(FBOs in general, depth buffers, stencil buffers, multiple render targets). 

This is a last-ditch effort of providing OpenGL 2.0 support. If you deem it too complex, simply limit support to OpenGL 3.0 (Core FBOs only).<br>
It's also very important to note that even though drivers *should* reroute extensions to Core calls when available, that's not guaranteed. 

We also need a proper way to get the currently binded framebuffer. Remember, this library will be used primarily in Minecraft. 
Other mods will be able to set up their own framebuffers which we DO NOT manage, and we would like to minimize the usage of "glGet" calls which we could manage with a custom "wrapping" approach with Mixins to those mods.
This point is probably the biggest case of increasing the difficulty of the supporting multiple types of framebuffers, since those mods could use different types as well.

Current pseudo-implementation of the idea is very bad, but it has some good ideas in it. 

### Shader abstractions
Nice way of creating & using shaders. Our shaders should only rely on GL20 extensions, however same issue as with framebuffers with other mods exist. 
<br>They could use extensions for the shaders, and we would usually want to bind back to their shaders in case we mess up *their* rendering process.

## Mid priority
### Checking GL capabilities
Very high-level checking of capabilities in the GL context, used for shader and framebuffer abstractions.

### Rendering approach.
So when we create our custom graphical effects in mods, we should be able to define a rendering approach. Basically setting up the shaders, creating the framebuffers required for it, and the code.<br>
Water-falling effect in case our approach requires capabilities not present on current hardware. 
This might be too high-level abstraction of an idea.
 