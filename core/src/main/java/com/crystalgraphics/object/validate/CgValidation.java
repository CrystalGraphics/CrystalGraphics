package com.crystalgraphics.object.validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.crystalgraphics.object.CgPropertyPath;

/**
 * What one validation found, collected as it runs: Unreal's {@code FDataValidationContext}. The engine's own checks
 * and every type's {@link CgValidator}s report into it.
 *
 * <pre>{@code
 * CgValidation v = data.validate(CgValidation.Usecase.LOAD);
 * if (!v.valid()) refuse(data.id(), v.issues());
 *
 * // In a validator: report at the node it was given, or at a key under it.
 * into.error(at.key("radius"), "negative_radius", "a radius is never negative");
 * }</pre>
 *
 * <ul>
 *   <li>{@link #valid()} counts errors only; warnings and infos never refuse an asset.</li>
 *   <li>One per run: not thread-safe, never shared between assets validated at once.</li>
 * </ul>
 */
public final class CgValidation {

    /** What asked for it, so a validator can skip work a use does not need: Unreal's {@code EDataValidationUsecase}. */
    public enum Usecase {
        /** An asset loading: an error refuses it, reported once. */
        LOAD,
        /** A cook: an error fails it. */
        COOK,
        /** The editor, live as the asset changes. */
        EDIT
    }

    private final Usecase usecase;
    private final List<CgIssue> issues = new ArrayList<>();
    private int errors, warnings;

    public CgValidation(Usecase usecase) {
        this.usecase = usecase;
    }

    /** What asked for it. */
    public Usecase usecase() {
        return usecase;
    }

    /** Reports an error at {@code path}. */
    public void error(CgPropertyPath path, String code, String message) {
        add(new CgIssue(path, CgIssue.Severity.ERROR, code, message));
    }

    /** Reports a warning at {@code path}. */
    public void warning(CgPropertyPath path, String code, String message) {
        add(new CgIssue(path, CgIssue.Severity.WARNING, code, message));
    }

    /** Reports an info at {@code path}. */
    public void info(CgPropertyPath path, String code, String message) {
        add(new CgIssue(path, CgIssue.Severity.INFO, code, message));
    }

    /** Reports {@code issue} as it is. */
    public void add(CgIssue issue) {
        issues.add(issue);
        if (issue.getSeverity() == CgIssue.Severity.ERROR) errors++;
        else if (issue.getSeverity() == CgIssue.Severity.WARNING) warnings++;
    }

    /** Every issue, in the order found. */
    public List<CgIssue> issues() {
        return Collections.unmodifiableList(issues);
    }

    /** How many errors. */
    public int errors() {
        return errors;
    }

    /** How many warnings. */
    public int warnings() {
        return warnings;
    }

    /** Whether it found no error. */
    public boolean valid() {
        return errors == 0;
    }
}
