package com.crystalgraphics.asset.load;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** One asset's prepare: its steps in order, run a frame's budget at a time. */
final class CgPreparePlan implements CgAssetPreparer.Plan {

    /** A step of the plan; true once done. Render thread. */
    interface Step {
        boolean advance(CgPrepareBudget budget);
    }

    final List<Step> steps = new ArrayList<>();
    long bytes;
    long uploaded;
    private int at;

    @Override
    public CgAssetPreparer.Plan upload(long bytes, CgAssetPreparer.Upload sink) {
        if (bytes <= 0) return this;
        this.bytes += bytes;
        steps.add(new Step() {
            long done;

            @Override
            public boolean advance(CgPrepareBudget budget) {
                while (done < bytes) {
                    long n = budget.piece(bytes - done);
                    if (n == 0) return false;
                    sink.piece(done, n);
                    done += n;
                    uploaded += n;
                    budget.uploaded(n);
                }
                return true;
            }
        });
        return this;
    }

    @Override
    public CgAssetPreparer.Plan warm(String what, BooleanSupplier attempt) {
        steps.add(budget -> {
            if (!budget.warmAllows()) return false;
            long t = System.nanoTime();
            boolean done = attempt.getAsBoolean();
            budget.warmed(System.nanoTime() - t);
            return done;
        });
        return this;
    }

    /** Runs steps in order under {@code budget}; answers whether every one is done. */
    boolean advance(CgPrepareBudget budget) {
        while (at < steps.size()) {
            if (!steps.get(at).advance(budget)) return false;
            at++;
        }
        return true;
    }
}
