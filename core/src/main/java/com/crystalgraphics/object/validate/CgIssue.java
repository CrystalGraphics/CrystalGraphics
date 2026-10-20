package com.crystalgraphics.object.validate;

import com.crystalgraphics.object.CgPropertyPath;

/**
 * One problem an asset's validation found: where, how bad, a stable code a tool can match on, and a message for a
 * person. Unreal's {@code FDataValidationContext::FIssue}, with the property path its tokenized message links to.
 *
 * <pre>{@code
 * for (CgIssue i : data.validate(CgValidation.Usecase.LOAD).issues()) {
 *     if (i.code().equals(CgIssue.UNKNOWN_KEY)) log.warn("{}", i);
 * }
 * // ERROR mymod:kamehameha/charge /emitters[h1]/sise: not a key of crystalgraphics:system.emitter (unknown_key)
 * }</pre>
 *
 * @param path     the node it is about, which an editor selects
 * @param severity how bad it is
 * @param code     a stable code a tool matches on
 * @param message  what a person reads
 */
public record CgIssue(CgPropertyPath path, Severity severity, String code, String message) {

    /** How bad it is: an error refuses the asset at load and fails a cook; a warning and an info only report. */
    public enum Severity { ERROR, WARNING, INFO }

    /** A key its type does not declare, in a type that is not open. */
    public static final String UNKNOWN_KEY = "unknown_key";
    /** A required key missing from the resolved asset. */
    public static final String REQUIRED = "required";
    /** A value of the wrong shape: a list where a map goes, a string where a number goes. */
    public static final String SHAPE = "shape";
    /** A value outside its enum. */
    public static final String ENUM = "enum";
    /** A value written in a form its property does not take: a {@code range} on a property that takes constants only. */
    public static final String FORM = "form";
    /** An element id that is not lower-case letters, digits and {@code _}, 1 to 32 of them. */
    public static final String ID = "id";
    /** An element id used twice in one asset. */
    public static final String DUPLICATE_ID = "duplicate_id";
    /** A reference that is not {@code namespace:path}. */
    public static final String REF = "ref";
    /** A family member its family does not have, or one with no key choosing it. */
    public static final String MEMBER = "member";
    /** An entry an importer could not map, kept in {@code unsupported}. */
    public static final String UNSUPPORTED = "unsupported";

    @Override
    public String toString() {
        return severity + " " + path + ": " + message + " (" + code + ")";
    }
}
