package com.crystalgraphics.freetype;

/**
 * FreeType error codes and error checking utility.
 * Maps integer error codes from FreeType's fterrdef.h to human-readable messages.
 */
public final class FTErrors {

    public static final int FT_Err_Ok = 0x00;
    public static final int FT_Err_Cannot_Open_Resource = 0x01;
    public static final int FT_Err_Unknown_File_Format = 0x02;
    public static final int FT_Err_Invalid_File_Format = 0x03;
    public static final int FT_Err_Invalid_Version = 0x04;
    public static final int FT_Err_Invalid_Argument = 0x06;
    public static final int FT_Err_Unimplemented_Feature = 0x07;
    public static final int FT_Err_Invalid_Glyph_Index = 0x10;
    public static final int FT_Err_Invalid_Character_Code = 0x11;
    public static final int FT_Err_Invalid_Glyph_Format = 0x12;
    public static final int FT_Err_Cannot_Render_Glyph = 0x13;
    public static final int FT_Err_Invalid_Pixel_Size = 0x17;
    public static final int FT_Err_Invalid_Handle = 0x20;
    public static final int FT_Err_Invalid_Library_Handle = 0x21;
    public static final int FT_Err_Invalid_Face_Handle = 0x23;
    public static final int FT_Err_Invalid_Size_Handle = 0x24;
    public static final int FT_Err_Out_Of_Memory = 0x40;
    public static final int FT_Err_Cannot_Open_Stream = 0x51;

    private FTErrors() {
    }

    public static String getMessage(int errorCode) {
        switch (errorCode) {
            case FT_Err_Ok: return "No error";
            case FT_Err_Cannot_Open_Resource: return "Cannot open resource";
            case FT_Err_Unknown_File_Format: return "Unknown file format";
            case FT_Err_Invalid_File_Format: return "Broken file";
            case FT_Err_Invalid_Argument: return "Invalid argument";
            case FT_Err_Unimplemented_Feature: return "Unimplemented feature";
            case FT_Err_Invalid_Glyph_Index: return "Invalid glyph index";
            case FT_Err_Invalid_Character_Code: return "Invalid character code";
            case FT_Err_Cannot_Render_Glyph: return "Cannot render this glyph format";
            case FT_Err_Invalid_Pixel_Size: return "Invalid pixel size";
            case FT_Err_Invalid_Handle: return "Invalid object handle";
            case FT_Err_Invalid_Library_Handle: return "Invalid library handle";
            case FT_Err_Invalid_Face_Handle: return "Invalid face handle";
            case FT_Err_Out_Of_Memory: return "Out of memory";
            case FT_Err_Cannot_Open_Stream: return "Cannot open stream";
            default: return "Unknown FreeType error (0x" + Integer.toHexString(errorCode) + ")";
        }
    }

    public static void checkError(int errorCode, String operation) {
        if (errorCode != FT_Err_Ok) {
            throw new FreeTypeException(errorCode, operation + ": " + getMessage(errorCode));
        }
    }
}
