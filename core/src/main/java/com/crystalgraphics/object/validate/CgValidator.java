package com.crystalgraphics.object.validate;

import java.util.Map;

import com.crystalgraphics.object.CgPropertyPath;

/**
 * A check a type makes of its own values beyond what its properties declare: Unreal's {@code UObject::IsDataValid}.
 * It runs on every value of its type in an asset, resolved over its parents, after the declared checks.
 *
 * <pre>{@code
 * CgTypes.element("mymod:renderer.arc")
 *         .property("min_width", CgValueType.FLOAT)
 *         .property("max_width", CgValueType.FLOAT)
 *         .validator((value, at, into) -> {
 *             if (value.get("min_width") instanceof Number && value.get("max_width") instanceof Number
 *                     && ((Number) value.get("min_width")).doubleValue() > ((Number) value.get("max_width")).doubleValue()) {
 *                 into.error(at.key("min_width"), "width_order", "min_width is above max_width");
 *             }
 *         })
 *         .register();
 * }</pre>
 *
 * <ul>
 *   <li>{@code value} is plain and resolved: a key may hold a form ({@code { range: [...] }}) where a number is
 *       declared, so check before casting.</li>
 *   <li>Report under {@code at}, never at a path of your own making: an editor links the issue to that node.</li>
 *   <li>Never change {@code value}.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgValidator {

    /** Checks {@code value}, the map at {@code at}, reporting what it finds into {@code into}. */
    void validate(Map<?, ?> value, CgPropertyPath at, CgValidation into);
}
