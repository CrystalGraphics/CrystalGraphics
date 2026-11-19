// The UI canvas's element: its paint is the record's (colour, image, mask, gradient, shadow, border), so its surface
// adds nothing. What CgCanvas draws an item with when the item names no material of its own.
#domain ui
Material { Primitive Element }

void surface(in CgUiInput i, inout CgUiSurface s) {
}
