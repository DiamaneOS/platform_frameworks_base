/* Copyright (C) 2026 DiamaneOS */
package com.android.internal.diamaneos.cleancopies;
import static org.junit.Assert.*;
import org.junit.Test;
public class ThumbnailAdmissionTest {
    @Test public void unfinishedProvidersKeepTheirSlots() {
        ThumbnailAdmission admission = new ThumbnailAdmission(3, 2);
        ThumbnailAdmission.Ticket first = admission.take("10@docs");
        ThumbnailAdmission.Ticket second = admission.take("10@docs");
        assertNull(admission.take("10@docs"));
        ThumbnailAdmission.Ticket other = admission.take("0@other");
        assertNotNull(other);
        assertNull(admission.take("third"));
        first.close();
        first.close();
        ThumbnailAdmission.Ticket next = admission.take("10@docs");
        assertNotNull(next);
        assertNull(admission.take("fourth"));
        second.close();
        other.close();
        next.close();
        assertNotNull(admission.take("10@docs"));
    }
}
