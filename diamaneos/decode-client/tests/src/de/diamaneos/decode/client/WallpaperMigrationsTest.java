/*
 * Copyright (C) 2026 DiamaneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import org.junit.Test;

public class WallpaperMigrationsTest {
    @Test
    public void staleExitAllowsALaterGenerationOnTheSamePath() {
        final WallpaperMigrations migrations = new WallpaperMigrations();
        assertTrue(migrations.begin("home", "old"));
        migrations.retire("home", "old");
        assertTrue(migrations.begin("home", "new"));
    }

    @Test
    public void retiredGenerationCannotRemoveItsSuccessorOrSuppressItsStart() {
        final WallpaperMigrations migrations = new WallpaperMigrations();
        assertTrue(migrations.begin("home", "old"));
        assertTrue(migrations.begin("home", "new"));
        migrations.retire("home", "old");
        assertFalse(migrations.begin("home", "new"));
    }

    @Test
    public void rejectedGenerationStaysSuppressedUntilItChanges() {
        final WallpaperMigrations migrations = new WallpaperMigrations();
        assertTrue(migrations.begin("home", "rejected"));
        assertFalse(migrations.begin("home", "rejected"));
        assertTrue(migrations.begin("home", "changed"));
    }
}
